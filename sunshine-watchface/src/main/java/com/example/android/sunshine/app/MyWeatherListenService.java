package com.example.android.sunshine.app;

import android.app.Service;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.IBinder;
import android.util.Log;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

public class MyWeatherListenService extends WearableListenerService {
    private static final String TAG = "SunListen";

    //the data item tags
    private final static String DATAITEM_PATH = "/sunshineWeather";
    private final static String DATAITEM_LOW_TEMP = "low";
    private final static String DATAITEM_HIGH_TEMP = "high";
    private final static String DATAITEM_ICONBM = "iconbm";

    /*
        Communication routines follow
     */
    @Override // DataApi.DataListener
    public void onDataChanged(DataEventBuffer dataEvents) {
        Log.v(TAG, "OnDataChanged");
        for (DataEvent dataEvent : dataEvents) {
            //if this event not a data changed, ignore
            if (dataEvent.getType() != DataEvent.TYPE_CHANGED) {
                continue;
            }

            //if this event is not a recognized data item, ignore.
            DataItem dataItem = dataEvent.getDataItem();
            if (!dataItem.getUri().getPath().equals(DATAITEM_PATH)) {
                continue;
            }

            Log.v(TAG, "ourData");

            DataMap weather = DataMapItem.fromDataItem(dataItem).getDataMap();

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "DataItem updated:" + weather);
            }

            //update our data
            updateDataFromDataMap(weather);
        }
    }

    //
    // utility which grabs out our data from the our datamap
    //
    private void updateDataFromDataMap(DataMap weather) {
        SunshineWatchFace.mLowTemp = weather.getInt(DATAITEM_LOW_TEMP);
        SunshineWatchFace.mHighTemp = weather.getInt(DATAITEM_HIGH_TEMP);

        Asset icon_asset = weather.getAsset(DATAITEM_ICONBM);
        if (SunshineWatchFace.mGoogleApiClient.isConnected()) {
            loadBitmapFromAsset(icon_asset);    //note - the "big" load (really only a couple of k)
        }
    }

    //recovers bitmap out of asset
    private void loadBitmapFromAsset(Asset asset) {
        if (asset == null) {
            return;
        }

        //Give 5 seconds for asset to download
        ConnectionResult result = SunshineWatchFace.mGoogleApiClient.blockingConnect(5000, TimeUnit.MILLISECONDS);

        if (!result.isSuccess()) {
            return;
        }

        //Convert asset into a file descriptor and block until ready
        InputStream assetInputStream = Wearable.DataApi.getFdForAsset(SunshineWatchFace.mGoogleApiClient, asset).await().getInputStream();
        SunshineWatchFace.mGoogleApiClient.disconnect();  //FIXME - should we disconnect here?

        if (assetInputStream == null) {
            return;
        }

        //otherwise decode stream
        SunshineWatchFace.mWeatherIconBM = BitmapFactory.decodeStream(assetInputStream);
    }

}
