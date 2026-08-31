package io.fastpix.uploads.java;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

// Calls the FastPix API to create an upload; delivers the result on the main thread.
public final class FastPixApi {
    private FastPixApi() {}

    public interface UrlCallback {
        void onUrl(String url);
        void onError(String message);
    }

    private static final String ENDPOINT = "https://api.fastpix.com/v1/on-demand/upload";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final String BODY =
            "{\"corsOrigin\":\"*\",\"pushMediaSettings\":{\"accessPolicy\":\"public\",\"maxResolution\":\"2160p\"}}";

    private static final OkHttpClient client = new OkHttpClient();
    private static final Handler main = new Handler(Looper.getMainLooper());

    public static void createUpload(UrlCallback cb) {
        String credentials = Config.TOKEN + ":" + Config.SECRET_KEY;
        String auth = "Basic " + Base64.encodeToString(credentials.getBytes(), Base64.NO_WRAP);
        Request request = new Request.Builder()
                .url(ENDPOINT)
                .header("Authorization", auth)
                .header("Content-Type", "application/json")
                .post(RequestBody.create(JSON, BODY))
                .build();
        client.newCall(request).enqueue(new Callback() {
            @Override public void onFailure(@NonNull Call call, @NonNull IOException e) {
                main.post(() -> cb.onError(e.getMessage()));
            }

            @Override public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response r = response) {
                    String body = r.body() != null ? r.body().string() : "";
                    if (!r.isSuccessful()) {
                        main.post(() -> cb.onError("HTTP " + r.code() + ": " + body));
                        return;
                    }
                    String url = new JSONObject(body).getJSONObject("data").getString("url");
                    main.post(() -> cb.onUrl(url));
                } catch (Exception e) {
                    main.post(() -> cb.onError(e.getMessage()));
                }
            }
        });
    }
}
