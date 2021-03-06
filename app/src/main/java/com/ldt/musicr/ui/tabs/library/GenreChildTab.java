package com.ldt.musicr.ui.tabs.library;

import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.Fragment;
import android.support.v4.widget.SwipeRefreshLayout;
import android.support.v7.graphics.Palette;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.model.GlideUrl;
import com.bumptech.glide.load.model.ModelLoader;
import com.ldt.musicr.R;
import com.ldt.musicr.addon.lastfm.rest.LastFMRestClient;
import com.ldt.musicr.addon.lastfm.rest.model.LastFmArtist;
import com.ldt.musicr.glide.artistimage.ArtistImageFetcher;
import com.ldt.musicr.model.Artist;
import com.ldt.musicr.ui.tabs.pager.ResultCallback;
import com.ldt.musicr.ui.widget.fragmentnavigationcontroller.SupportFragment;
import com.ldt.musicr.util.LastFMUtil;
import com.ldt.musicr.util.MusicUtil;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;

import butterknife.BindView;
import butterknife.ButterKnife;
import retrofit2.Callback;
import retrofit2.Response;

public class GenreChildTab extends Fragment implements ResultCallback {
    private static final String TAG ="ArtistTrialPager";
    // we need these very low values to make sure our artist image loading calls doesn't block the image loading queue
    private static final int TIMEOUT = 750;

    private static final String ARTIST = "artist";

    public static GenreChildTab newInstance(Artist artist) {

        Bundle args = new Bundle();
        if(artist!=null)
            args.putParcelable(ARTIST,artist);

        GenreChildTab fragment = new GenreChildTab();
        fragment.setArguments(args);
        return fragment;
    }

    @BindView(R.id.image)
    ImageView mImage;

    @BindView(R.id.text)
    TextView mText;

    @BindView(R.id.swipe_refresh)
    SwipeRefreshLayout mSwipeRefresh;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.genre_child_tab,container,false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ButterKnife.bind(this,view);
        init();
        mSwipeRefresh.setOnRefreshListener(this::updateData);
    }

    private void updateData() {
        if(mArtist!=null) {
            tryToLoadArtistImage(mArtist,this);
        } else mSwipeRefresh.setRefreshing(false);
    }
    Artist mArtist;

    LastFMRestClient mLastFmClient;
    private void init() {
        Bundle bundle = getArguments();
        if(bundle!=null) {
            mArtist = bundle.getParcelable(ARTIST);
        }
        if(mArtist!=null)
        mLastFmClient = new LastFMRestClient(LastFMRestClient.createDefaultOkHttpClientBuilder(getContext())
                .connectTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .readTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .writeTimeout(TIMEOUT, TimeUnit.MILLISECONDS)
                .build());
    }
    private boolean isCancelled = false;
    private boolean mSkipOkHttpCache = false;
    private boolean mLoadOriginal = true;


    @Override
    public void onSuccess(String url) {
        Log.d(TAG, "onSuccess: url = "+url);
        Glide.with(this).load(url).into(mImage);
        mSwipeRefresh.setRefreshing(false);
    }

    @Override
    public void onFailure(Exception e) {
        Log.d(TAG, "onFailure: e = "+ e.getMessage());
        mSwipeRefresh.setRefreshing(false);
    }

    @Override
    public void onSuccess(ArrayList<String> mResult) {

    }

    private void tryToLoadArtistImage(Artist artist, ResultCallback callback) {
        long start = System.currentTimeMillis();

        if (!MusicUtil.isArtistNameUnknown(artist.getName())/* && PreferenceUtil.isAllowedToDownloadMetadata(context)*/) {
            // Try to get the group image
            String artistNames = artist.getName();
            artistNames = artistNames
                    .replace(" ft "," & ")
                    .replace(";"," & ")
                    .replace(","," & ")
                    .replaceAll("( +)"," ").trim();
            Log.d(TAG, start + " afterArtist =["+ artistNames+"]");
            if(null==loadThisArtist(artistNames,callback)) return;
            if(null == loadThisArtist(artistNames.replace("&",", ").replaceAll("( +)"," ").trim().replaceAll("\\s+(?=[),])", ""),callback)) return;

            // if not, try to get one of artist image
            Exception e = null;
            String[] artists = artistNames.split("&");

            String log = "";
            for (String a :
                    artists) {
                log += " ["+a+"] ";
            }
            Log.d(TAG, start+" afterSplit ="+log);

            if (artists.length == 0) {
                callback.onFailure(new NullPointerException("Artist is empty"));
            }
            for (String artistName : artists) {
                if (artistName.isEmpty()) {
                    e = new Exception("Empty Artist");
                    continue;
                }
                e = loadThisArtist(artistName.trim(), callback);
                if (e == null) break;
            }
            if (e != null) callback.onFailure(e);
        } else callback.onFailure(new Exception("Unknown Artist"));
    }

    private Exception loadThisArtist(String artistName, ResultCallback callback) {
        Response<LastFmArtist> response;
        try {
            response = mLastFmClient.getApiService().getArtistInfo(artistName, null, mSkipOkHttpCache ? "no-cache" : null).execute();
            Log.d(TAG, "loadData: artistName = ["+artistName+"] : succeed");
        } catch (Exception e) {
            Log.d(TAG, "loadData: artistName = ["+artistName+"] : exception");
            return e;
        }

        if(!response.isSuccessful())
            return new IOException("Request failed with code: " + response.code());
        else {
            LastFmArtist lastFmArtist = response.body();
            Log.d(TAG, "loadData: "+lastFmArtist);

            if (isCancelled) {
                return new Exception("Cancelled");
            }

            if (lastFmArtist == null || lastFmArtist.getArtist() == null) {
                return new NullPointerException("Artist is null");
            }

            String largestArtistImageUrl = LastFMUtil.getLargestArtistImageUrl(lastFmArtist.getArtist().getImage());
            if(largestArtistImageUrl!=null&&!largestArtistImageUrl.isEmpty()) {

                if(mLoadOriginal) largestArtistImageUrl = ArtistImageFetcher.findAndReplaceToGetOriginal(largestArtistImageUrl);
                Log.d(TAG, "loadThisArtist: url = ["+largestArtistImageUrl+"]");
                callback.onSuccess(largestArtistImageUrl);
            } else return new Exception("No Artist Image is available : \n"+lastFmArtist.toString());
        }
        return null;
    }
}

