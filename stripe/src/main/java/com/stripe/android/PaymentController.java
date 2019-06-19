package com.stripe.android;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.VisibleForTesting;
import android.support.v4.content.LocalBroadcastManager;

import com.stripe.android.exception.StripeException;
import com.stripe.android.model.PaymentIntent;
import com.stripe.android.model.PaymentIntentParams;
import com.stripe.android.model.Stripe3ds2AuthResult;
import com.stripe.android.model.Stripe3ds2Fingerprint;
import com.stripe.android.model.StripeIntent;
import com.stripe.android.stripe3ds2.init.StripeConfigParameters;
import com.stripe.android.stripe3ds2.service.StripeThreeDs2Service;
import com.stripe.android.stripe3ds2.service.StripeThreeDs2ServiceImpl;
import com.stripe.android.stripe3ds2.transaction.AuthenticationRequestParameters;
import com.stripe.android.stripe3ds2.transaction.CompletionEvent;
import com.stripe.android.stripe3ds2.transaction.MessageVersionRegistry;
import com.stripe.android.stripe3ds2.transaction.ProtocolErrorEvent;
import com.stripe.android.stripe3ds2.transaction.RuntimeErrorEvent;
import com.stripe.android.stripe3ds2.transaction.StripeChallengeParameters;
import com.stripe.android.stripe3ds2.transaction.StripeChallengeStatusReceiver;
import com.stripe.android.stripe3ds2.transaction.Transaction;
import com.stripe.android.view.ActivityStarter;
import com.stripe.android.view.StripeIntentResultExtras;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static com.ults.listeners.SdkChallengeInterface.UL_HANDLE_CHALLENGE_ACTION;

/**
 * A controller responsible for confirming and authenticating payment (typically through resolving
 * any required customer action). The payment authentication mechanism (e.g. 3DS) will be determined
 * by the {@link PaymentIntent} object.
 */
class PaymentController {
    static final int REQUEST_CODE = 50000;

    @NonNull private final StripeThreeDs2Service mThreeDs2Service;
    @NonNull private final StripeApiHandler mApiHandler;
    @NonNull private final MessageVersionRegistry mMessageVersionRegistry;
    @NonNull private final PaymentAuthConfig mConfig;
    @NonNull private final ApiKeyValidator mApiKeyValidator;

    PaymentController(@NonNull Context context,
                      @NonNull StripeApiHandler apiHandler) {
        this(context, new StripeThreeDs2ServiceImpl(context), apiHandler,
                new MessageVersionRegistry(),
                PaymentAuthConfig.get());
    }

    @VisibleForTesting
    PaymentController(@NonNull Context context,
                      @NonNull StripeThreeDs2Service threeDs2Service,
                      @NonNull StripeApiHandler apiHandler,
                      @NonNull MessageVersionRegistry messageVersionRegistry,
                      @NonNull PaymentAuthConfig config) {
        mConfig = config;
        mThreeDs2Service = threeDs2Service;
        mThreeDs2Service.initialize(context, new StripeConfigParameters(), null,
                config.stripe3ds2Config.uiCustomization.getUiCustomization());
        mApiHandler = apiHandler;
        mMessageVersionRegistry = messageVersionRegistry;
        mApiKeyValidator = new ApiKeyValidator();
    }

    /**
     * Confirm the PaymentIntent and resolve any next actions
     */
    void startConfirmAndAuth(@NonNull Stripe stripe,
                             @NonNull Activity activity,
                             @NonNull PaymentIntentParams paymentIntentParams,
                             @NonNull String publishableKey) {
        mApiKeyValidator.requireValid(publishableKey);
        new ConfirmStripeIntentTask(stripe, paymentIntentParams, publishableKey,
                new ConfirmPaymentIntentCallback(activity, publishableKey, this))
                .execute();
    }

    void startAuth(@NonNull Activity activity,
                   @NonNull StripeIntent stripeIntent,
                   @NonNull String publishableKey) {
        handleNextAction(activity, stripeIntent, mApiKeyValidator.requireValid(publishableKey));
    }

    /**
     * Decide whether {@link #handleResult(Stripe, Intent, String, ApiResultCallback)} should be
     * called.
     */
    boolean shouldHandleResult(int requestCode, int resultCode, @Nullable Intent data) {
        return requestCode == REQUEST_CODE &&
                resultCode == Activity.RESULT_OK &&
                data != null;
    }

    /**
     * If payment authentication triggered an exception, get the exception object and pass to
     * {@link ApiResultCallback#onError(Exception)}.
     *
     * Otherwise, get the PaymentIntent's client_secret from {@param data} and use to retrieve the
     * PaymentIntent object with updated status.
     *
     * @param data the result Intent
     */
    void handleResult(@NonNull Stripe stripe, @NonNull Intent data, @NonNull String publishableKey,
                      @NonNull final ApiResultCallback<PaymentIntentResult> callback) {
        final Exception authException = (Exception) data.getSerializableExtra(
                StripeIntentResultExtras.AUTH_EXCEPTION);
        if (authException != null) {
            callback.onError(authException);
            return;
        }

        @StripeIntentResult.Status final int authStatus = data.getIntExtra(
                StripeIntentResultExtras.AUTH_STATUS, StripeIntentResult.Status.UNKNOWN);
        new RetrieveIntentTask(stripe, createPaymentIntentParams(data), publishableKey,
                new ApiResultCallback<StripeIntent>() {
                    @Override
                    public void onSuccess(@NonNull StripeIntent stripeIntent) {
                        if (stripeIntent instanceof PaymentIntent) {
                            callback.onSuccess(new PaymentIntentResult.Builder<>()
                                    .setPaymentIntent((PaymentIntent) stripeIntent)
                                    .setStatus(authStatus)
                                    .build());
                        }
                    }

                    @Override
                    public void onError(@NonNull Exception e) {
                        callback.onError(e);
                    }
                })
                .execute();
    }

    @NonNull
    private PaymentIntentParams createPaymentIntentParams(@NonNull Intent data) {
        final String clientSecret = data.getStringExtra(StripeIntentResultExtras.CLIENT_SECRET);
        return PaymentIntentParams.createRetrievePaymentIntentParams(clientSecret);
    }

    /**
     * Determine which authentication mechanism should be used, or bypass authentication
     * if it is not needed.
     */
    @VisibleForTesting
    void handleNextAction(@NonNull Activity activity,
                          @NonNull StripeIntent stripeIntent,
                          @NonNull String publishableKey) {
        if (stripeIntent.requiresAction()) {
            final StripeIntent.NextActionType nextActionType = stripeIntent.getNextActionType();
            if (StripeIntent.NextActionType.UseStripeSdk == nextActionType) {
                final StripeIntent.SdkData sdkData =
                        Objects.requireNonNull(stripeIntent.getStripeSdkData());
                if (sdkData.is3ds2()) {
                    begin3ds2Auth(activity, stripeIntent,
                            Stripe3ds2Fingerprint.create(sdkData), publishableKey);
                } else {
                    // authentication type is not supported
                    bypassAuth(activity, stripeIntent);
                }
            } else if (StripeIntent.NextActionType.RedirectToUrl == nextActionType) {
                begin3ds1Auth(activity,
                        Objects.requireNonNull(stripeIntent.getRedirectData()));
            } else {
                // next action type is not supported, so bypass authentication
                bypassAuth(activity, stripeIntent);
            }
        } else {
            // no action required, so bypass authentication
            bypassAuth(activity, stripeIntent);
        }
    }

    private void bypassAuth(@NonNull Activity activity, @NonNull StripeIntent stripeIntent) {
        new PaymentRelayStarter(activity, REQUEST_CODE)
                .start(new PaymentRelayStarter.Data(stripeIntent));
    }

    private void begin3ds2Auth(@NonNull Activity activity,
                               @NonNull StripeIntent stripeIntent,
                               @NonNull Stripe3ds2Fingerprint stripe3ds2Fingerprint,
                               @NonNull String publishableKey) {
        final Transaction transaction =
                mThreeDs2Service.createTransaction(stripe3ds2Fingerprint.directoryServer.id,
                        mMessageVersionRegistry.getCurrent(), false,
                        stripe3ds2Fingerprint.directoryServer.name);
        final ProgressDialog dialog = transaction.getProgressView(activity);
        dialog.show();

        final LocalBroadcastManager localBroadcastManager =
                LocalBroadcastManager.getInstance(activity);
        localBroadcastManager.registerReceiver(new DialogBroadcastReceiver(localBroadcastManager,
                        dialog), new IntentFilter(UL_HANDLE_CHALLENGE_ACTION));

        final AuthenticationRequestParameters areqParams =
                transaction.getAuthenticationRequestParameters();
        final int timeout = mConfig.stripe3ds2Config.timeout;
        final Stripe3ds2AuthParams authParams = new Stripe3ds2AuthParams(
                stripe3ds2Fingerprint.source,
                areqParams.getSDKAppID(),
                areqParams.getSDKReferenceNumber(),
                areqParams.getSDKTransactionID(),
                areqParams.getDeviceData(),
                areqParams.getSDKEphemeralPublicKey(),
                areqParams.getMessageVersion(),
                timeout
        );
        mApiHandler.start3ds2Auth(authParams, publishableKey,
                new Stripe3ds2AuthCallback(activity, mApiHandler, transaction, timeout,
                        stripeIntent, stripe3ds2Fingerprint.source, publishableKey));
    }

    /**
     * Start in-app WebView activity.
     *
     * @param activity the payment authentication result will be returned as a result to this
     *         {@link Activity}
     */
    private void begin3ds1Auth(@NonNull Activity activity,
                               @NonNull StripeIntent.RedirectData redirectData) {
        new PaymentAuthWebViewStarter(activity, REQUEST_CODE).start(redirectData);
    }

    private static void handleError(@NonNull Activity activity,
                                    @NonNull Exception exception) {
        new PaymentRelayStarter(activity, REQUEST_CODE)
                .start(new PaymentRelayStarter.Data(exception));
    }

    private static final class RetrieveIntentTask extends ApiOperation<StripeIntent> {
        @NonNull private final Stripe mStripe;
        @NonNull private final PaymentIntentParams mParams;
        @NonNull private final String mPublishableKey;

        private RetrieveIntentTask(@NonNull Stripe stripe,
                                   @NonNull PaymentIntentParams params,
                                   @NonNull String publishableKey,
                                   @NonNull ApiResultCallback<StripeIntent> callback) {
            super(callback);
            mStripe = stripe;
            mParams = params;
            mPublishableKey = publishableKey;
        }

        @Nullable
        @Override
        PaymentIntent getResult() throws StripeException {
            return mStripe.retrievePaymentIntentSynchronous(mParams, mPublishableKey);
        }
    }

    private static final class ConfirmStripeIntentTask extends ApiOperation<StripeIntent> {
        @NonNull private final Stripe mStripe;
        @NonNull private final PaymentIntentParams mParams;
        @NonNull private final String mPublishableKey;

        private ConfirmStripeIntentTask(@NonNull Stripe stripe,
                                        @NonNull PaymentIntentParams params,
                                        @NonNull String publishableKey,
                                        @NonNull ApiResultCallback<StripeIntent> callback) {
            super(callback);
            mStripe = stripe;
            mParams = params;
            mPublishableKey = publishableKey;
        }

        @Nullable
        @Override
        PaymentIntent getResult() throws StripeException {
            return mStripe.confirmPaymentIntentSynchronous(mParams, mPublishableKey);
        }
    }

    private static final class ConfirmPaymentIntentCallback
            implements ApiResultCallback<StripeIntent> {
        @NonNull private final WeakReference<Activity> mActivityRef;
        @NonNull private final String mPublishableKey;
        @NonNull private final PaymentController mPaymentController;

        private ConfirmPaymentIntentCallback(
                @NonNull Activity activity,
                @NonNull String publishableKey,
                @NonNull PaymentController paymentController) {
            mActivityRef = new WeakReference<>(activity);
            mPublishableKey = publishableKey;
            mPaymentController = paymentController;
        }

        @Override
        public void onSuccess(@NonNull StripeIntent stripeIntent) {
            final Activity activity = mActivityRef.get();
            if (activity != null) {
                mPaymentController.handleNextAction(activity, stripeIntent, mPublishableKey);
            }
        }

        @Override
        public void onError(@NonNull Exception e) {
            final Activity activity = mActivityRef.get();
            if (activity != null) {
                handleError(activity, e);
            }
        }
    }

    static final class Stripe3ds2AuthCallback
            implements ApiResultCallback<Stripe3ds2AuthResult> {
        @NonNull private final WeakReference<Activity> mActivityRef;
        @NonNull private final StripeApiHandler mApiHandler;
        @NonNull private final Transaction mTransaction;
        private final int mMaxTimeout;
        @NonNull private final StripeIntent mStripeIntent;
        @NonNull private final String mSourceId;
        @NonNull private final String mPublishableKey;
        @NonNull private final PaymentRelayStarter mPaymentRelayStarter;
        @NonNull private final Handler mBackgroundHandler;

        private Stripe3ds2AuthCallback(
                @NonNull Activity activity,
                @NonNull StripeApiHandler apiHandler,
                @NonNull Transaction transaction,
                int maxTimeout,
                @NonNull StripeIntent stripeIntent,
                @NonNull String sourceId,
                @NonNull String publishableKey) {
            this(activity, apiHandler, transaction, maxTimeout, stripeIntent,
                    sourceId, publishableKey, new PaymentRelayStarter(activity, REQUEST_CODE));
        }

        @VisibleForTesting
        Stripe3ds2AuthCallback(
                @NonNull Activity activity,
                @NonNull StripeApiHandler apiHandler,
                @NonNull Transaction transaction,
                int maxTimeout,
                @NonNull StripeIntent stripeIntent,
                @NonNull String sourceId,
                @NonNull String publishableKey,
                @NonNull PaymentRelayStarter paymentRelayStarter) {
            mActivityRef = new WeakReference<>(activity);
            mApiHandler = apiHandler;
            mTransaction = transaction;
            mMaxTimeout = maxTimeout;
            mStripeIntent = stripeIntent;
            mSourceId = sourceId;
            mPublishableKey = publishableKey;
            mPaymentRelayStarter = paymentRelayStarter;

            // create Handler to notifyCompletion challenge flow on background thread
            final HandlerThread handlerThread =
                    new HandlerThread(Stripe3ds2AuthCallback.class.getSimpleName());
            handlerThread.start();
            mBackgroundHandler = new Handler(handlerThread.getLooper());
        }

        @Override
        public void onSuccess(@NonNull Stripe3ds2AuthResult result) {
            final Activity activity = mActivityRef.get();
            if (activity == null) {
                return;
            }

            final Stripe3ds2AuthResult.Ares ares = result.ares;
            if (ares != null) {
                if (ares.shouldChallenge()) {
                    startChallengeFlow(activity, ares);
                } else {
                    startFrictionlessFlow();
                }
            } else {
                final Stripe3ds2AuthResult.ThreeDS2Error error = result.error;
                final String errorMessage;
                if (error != null) {
                    errorMessage = "Code: " + error.errorCode +
                            ", Detail: " + error.errorDetail +
                            ", Description: " + error.errorDescription +
                            ", Component: " + error.errorComponent;
                } else {
                    errorMessage = "Invalid 3DS2 authentication response";
                }

                onError(new RuntimeException(
                        "Error encountered during 3DS2 authentication request. " + errorMessage));
            }
        }

        @Override
        public void onError(@NonNull Exception e) {
            final Activity activity = mActivityRef.get();
            if (activity != null) {
                mPaymentRelayStarter.start(new PaymentRelayStarter.Data(e));
            }
        }

        private void startFrictionlessFlow() {
            mPaymentRelayStarter.start(new PaymentRelayStarter.Data(mStripeIntent));
        }

        private void startChallengeFlow(@NonNull final Activity activity,
                                        @NonNull Stripe3ds2AuthResult.Ares ares) {
            final StripeChallengeParameters challengeParameters =
                    new StripeChallengeParameters();
            challengeParameters.setAcsSignedContent(ares.acsSignedContent);
            challengeParameters.set3DSServerTransactionID(ares.threeDSServerTransId);
            challengeParameters.setAcsTransactionID(ares.acsTransId);

            mBackgroundHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    mTransaction.doChallenge(activity,
                            challengeParameters,
                            PaymentAuth3ds2ChallengeStatusReceiver.create(activity, mApiHandler,
                                    mStripeIntent, mSourceId, mPublishableKey),
                            mMaxTimeout);
                }
            }, TimeUnit.SECONDS.toMillis(2));
        }
    }

    static final class PaymentAuth3ds2ChallengeStatusReceiver
            extends StripeChallengeStatusReceiver {
        @NonNull private final WeakReference<Activity> mActivityRef;
        @NonNull private final ActivityStarter<Stripe3ds2CompletionStarter.StartData> mStarter;
        @NonNull private final StripeApiHandler mApiHandler;
        @NonNull private final StripeIntent mStripeIntent;
        @NonNull private final String mSourceId;
        @NonNull private final String mPublishableKey;

        @NonNull
        static PaymentAuth3ds2ChallengeStatusReceiver create(
                @NonNull Activity activity,
                @NonNull StripeApiHandler apiHandler,
                @NonNull StripeIntent stripeIntent,
                @NonNull String sourceId,
                @NonNull String publishableKey) {
            return new PaymentAuth3ds2ChallengeStatusReceiver(
                    activity,
                    new Stripe3ds2CompletionStarter(activity, REQUEST_CODE),
                    apiHandler,
                    stripeIntent,
                    sourceId,
                    publishableKey);
        }

        PaymentAuth3ds2ChallengeStatusReceiver(
                @NonNull Activity activity,
                @NonNull ActivityStarter<Stripe3ds2CompletionStarter.StartData> starter,
                @NonNull StripeApiHandler apiHandler,
                @NonNull StripeIntent stripeIntent,
                @NonNull String sourceId,
                @NonNull String publishableKey) {
            mActivityRef = new WeakReference<>(activity);
            mStarter = starter;
            mApiHandler = apiHandler;
            mStripeIntent = stripeIntent;
            mSourceId = sourceId;
            mPublishableKey = publishableKey;
        }

        @Override
        public void completed(@NonNull CompletionEvent completionEvent) {
            super.completed(completionEvent);
            notifyCompletion(Stripe3ds2CompletionStarter.StartData.createForComplete(mStripeIntent,
                    completionEvent.getTransactionStatus()));
        }

        @Override
        public void cancelled() {
            super.cancelled();
            notifyCompletion(new Stripe3ds2CompletionStarter.StartData(mStripeIntent,
                    Stripe3ds2CompletionStarter.ChallengeFlowOutcome.CANCEL));
        }

        @Override
        public void timedout() {
            super.timedout();
            notifyCompletion(new Stripe3ds2CompletionStarter.StartData(mStripeIntent,
                    Stripe3ds2CompletionStarter.ChallengeFlowOutcome.TIMEOUT));
        }

        @Override
        public void protocolError(@NonNull ProtocolErrorEvent protocolErrorEvent) {
            super.protocolError(protocolErrorEvent);
            notifyCompletion(new Stripe3ds2CompletionStarter.StartData(mStripeIntent,
                    Stripe3ds2CompletionStarter.ChallengeFlowOutcome.PROTOCOL_ERROR));
        }

        @Override
        public void runtimeError(@NonNull RuntimeErrorEvent runtimeErrorEvent) {
            super.runtimeError(runtimeErrorEvent);
            notifyCompletion(new Stripe3ds2CompletionStarter.StartData(mStripeIntent,
                    Stripe3ds2CompletionStarter.ChallengeFlowOutcome.RUNTIME_ERROR));
        }

        private void notifyCompletion(
                @NonNull final Stripe3ds2CompletionStarter.StartData startData) {
            mApiHandler.complete3ds2Auth(mSourceId, mPublishableKey,
                    new ApiResultCallback<Boolean>() {
                        @Override
                        public void onSuccess(@NonNull Boolean result) {
                            mStarter.start(startData);
                        }

                        @Override
                        public void onError(@NonNull Exception e) {
                            final Activity activity = mActivityRef.get();
                            if (activity != null) {
                                handleError(activity, e);
                            }
                        }
                    });
        }
    }

    private static class DialogBroadcastReceiver extends BroadcastReceiver {

        @NonNull private final LocalBroadcastManager mLocalBroadcastManager;
        @NonNull private final WeakReference<ProgressDialog> mProgressDialogRef;

        DialogBroadcastReceiver(@NonNull LocalBroadcastManager localBroadcastManager,
                                       @NonNull ProgressDialog progressDialog) {
            mProgressDialogRef = new WeakReference<>(progressDialog);
            mLocalBroadcastManager = localBroadcastManager;
        }

        @Override
        public void onReceive(@NonNull Context context, @NonNull Intent intent) {
            final ProgressDialog dialog = mProgressDialogRef.get();
            if (dialog != null) {
                dialog.dismiss();
            }
            mLocalBroadcastManager.unregisterReceiver(this);
        }
    }
}