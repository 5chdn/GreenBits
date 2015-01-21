package com.greenaddress.greenbits.ui;


import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.common.base.Function;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.greenaddress.greenbits.ConnectivityObservable;
import com.greenaddress.greenbits.GreenAddressApplication;
import com.greenaddress.greenbits.QrBitmap;

import javax.annotation.Nullable;


public class ReceiveFragment extends Fragment {
    FutureCallback<QrBitmap> onAddress = null;
    private int curSubaccount;

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
                             final Bundle savedInstanceState) {
        curSubaccount = getActivity().getSharedPreferences("receive", Context.MODE_PRIVATE).getInt("curSubaccount", 0);

        final View rootView = inflater.inflate(R.layout.fragment_receive, container, false);
        final TextView receiveAddress = (TextView) rootView.findViewById(R.id.receiveAddressText);
        final TextView copyIcon = (TextView) rootView.findViewById(R.id.receiveCopyIcon);
        copyIcon.setVisibility(View.GONE);

        final TextView newAddressIcon = (TextView) rootView.findViewById(R.id.receiveNewAddressIcon);
        final ImageView imageView = (ImageView) rootView.findViewById(R.id.receiveQrImageView);
        final Animation iconPressed = AnimationUtils.loadAnimation(getActivity(), R.anim.icon_pressed);
        copyIcon.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(final View view) {
                        copyIcon.startAnimation(iconPressed);
                        // Gets a handle to the clipboard service.
                        final ClipboardManager clipboard = (ClipboardManager)
                                getActivity().getSystemService(Context.CLIPBOARD_SERVICE);
                        final ClipData clip = ClipData.newPlainText("data", receiveAddress.getText().toString().replace("\n", ""));
                        clipboard.setPrimaryClip(clip);

                        final CharSequence text = getActivity().getString(R.string.toastOnCopyAddress) + " " + getActivity().getString(R.string.warnOnPaste);

                        Toast.makeText(getActivity(), text, Toast.LENGTH_LONG).show();

                    }
                }
        );
        final Animation rotateAnim = AnimationUtils.loadAnimation(getActivity(), R.anim.rotation);
        newAddressIcon.startAnimation(rotateAnim);

        onAddress = new FutureCallback<QrBitmap>() {
            @Override
            public void onSuccess(@Nullable final QrBitmap result) {
                final Activity activity = getActivity();
                if (activity != null) {
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {

                            copyIcon.setVisibility(View.VISIBLE);
                            newAddressIcon.clearAnimation();
                            imageView.setImageBitmap(result.qrcode);

                            receiveAddress.setText(result.data.substring(0, 12) + "\n" + result.data.substring(12, 24) + "\n" + result.data.substring(24));
                        }
                    });
                }
            }

            @Override
            public void onFailure(final Throwable t) {
                final Activity activity = getActivity();
                if (activity != null) {
                    activity.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(getActivity(), "Can't get a new address", Toast.LENGTH_LONG).show();
                            newAddressIcon.clearAnimation();
                            copyIcon.setVisibility(View.VISIBLE);
                        }
                    });
                }
            }
        };

        newAddressIcon.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(final View view) {
                        if (!((GreenAddressApplication) getActivity().getApplication()).getConnectionObservable().getState().equals(ConnectivityObservable.State.LOGGEDIN)) {
                            Toast.makeText(getActivity(), "Not connected, connection will resume automatically", Toast.LENGTH_LONG).show();
                            return;
                        }
                        copyIcon.setVisibility(View.GONE);
                        receiveAddress.setText("");
                        imageView.setImageBitmap(null);
                        newAddressIcon.startAnimation(rotateAnim);

                        final ListenableFuture<QrBitmap> ft = ((GreenAddressApplication) getActivity().getApplication()).gaService.getNewAddress(curSubaccount);
                        Futures.addCallback(ft, onAddress, ((GreenAddressApplication) getActivity().getApplication()).gaService.es);
                    }
                }
        );

        ((GreenAddressApplication) getActivity().getApplication()).configureSubaccountsFooter(
                curSubaccount,
                getActivity(),
                (TextView) rootView.findViewById(R.id.sendAccountName),
                (LinearLayout) rootView.findViewById(R.id.receiveFooter),
                (LinearLayout) rootView.findViewById(R.id.footerClickableArea),
                new Function<Integer, Void>() {
                    @Nullable
                    @Override
                    public Void apply(@Nullable Integer input) {
                        curSubaccount = input;
                        final SharedPreferences.Editor editor = getActivity().getSharedPreferences("receive", Context.MODE_PRIVATE).edit();
                        editor.putInt("curSubaccount", curSubaccount);
                        editor.apply();
                        copyIcon.setVisibility(View.GONE);
                        receiveAddress.setText("");
                        imageView.setImageBitmap(null);
                        newAddressIcon.startAnimation(rotateAnim);
                        Futures.addCallback(
                                ((GreenAddressApplication) getActivity().getApplication()).gaService.getLatestOrNewAddress(curSubaccount),
                                onAddress, ((GreenAddressApplication) getActivity().getApplication()).gaService.es);
                        return null;
                    }
                }
        );

        return rootView;
    }

    @Override
    public void onResume() {
        super.onResume();
        Futures.addCallback(
                ((GreenAddressApplication) getActivity().getApplication()).gaService.getLatestOrNewAddress(curSubaccount),
                onAddress, ((GreenAddressApplication) getActivity().getApplication()).gaService.es);
    }

    @Override
    public void onPause() {
        super.onPause();
    }
}