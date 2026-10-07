package tw.nekomimi.nekogram.helpers;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.text.TextUtils;
import android.util.Base64;
import android.util.TypedValue;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import com.google.gson.Gson;
import com.google.gson.annotations.Expose;
import com.google.gson.annotations.SerializedName;
import com.google.net.cronet.okhttptransport.CronetCallFactory;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BotWebViewVibrationEffect;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import tw.nekomimi.nekogram.NekoConfig;

public class WhisperHelper {
    private static Call.Factory okHttpClient;
    private static final Gson gson = new Gson();
    private static final ExecutorService executorService = Executors.newCachedThreadPool();

    public static boolean useWorkersAi(int account) {
        return NekoConfig.transcribeProvider == NekoConfig.TRANSCRIBE_WORKERSAI || (!UserConfig.getInstance(account).isPremium() && NekoConfig.transcribeProvider == NekoConfig.TRANSCRIBE_AUTO);
    }

    public static void showErrorDialog(Exception e) {
        var fragment = LaunchActivity.getSafeLastFragment();
        var message = e.getLocalizedMessage();
        if (!BulletinFactory.canShowBulletin(fragment) || message == null) {
            return;
        }
        if (message.length() > 45) {
            AlertsCreator.showSimpleAlert(fragment, LocaleController.getString(R.string.ErrorOccurred), e.getMessage());
        } else {
            BulletinFactory.of(fragment).createErrorBulletin(message).show();
        }
    }

    public static void showCfCredentialsDialog(BaseFragment fragment) {
        var resourcesProvider = fragment.getResourceProvider();
        var context = fragment.getParentActivity();
        var builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle("Google Gemini API");
        builder.setMessage("Введите API ключ, полученный в Google AI Studio (aistudio.google.com)");
        builder.setCustomViewOffset(0);

        var ll = new LinearLayout(context);
        ll.setOrientation(LinearLayout.VERTICAL);

        var editTextApiKey = new EditTextBoldCursor(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(64), MeasureSpec.EXACTLY));
            }
        };
        editTextApiKey.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        editTextApiKey.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        editTextApiKey.setText(NekoConfig.cfApiToken);
        editTextApiKey.setHintText("Gemini API Key");
        editTextApiKey.setHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        editTextApiKey.setHeaderHintColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, resourcesProvider));
        editTextApiKey.setSingleLine(true);
        editTextApiKey.setFocusable(true);
        editTextApiKey.setTransformHintToHeader(true);
        editTextApiKey.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField, resourcesProvider), Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, resourcesProvider), Theme.getColor(Theme.key_text_RedRegular, resourcesProvider));
        editTextApiKey.setImeOptions(EditorInfo.IME_ACTION_DONE);
        editTextApiKey.setBackground(null);
        editTextApiKey.requestFocus();
        editTextApiKey.setPadding(0, 0, 0, 0);
        ll.addView(editTextApiKey, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, 0, 24, 0, 24, 0));

        builder.setView(ll);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        var dialog = builder.create();
        fragment.showDialog(dialog);
        var button = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (button != null) {
            button.setOnClickListener(v -> {
                var apiKey = editTextApiKey.getText();
                if (TextUtils.isEmpty(apiKey)) {
                    AndroidUtilities.shakeViewSpring(editTextApiKey, -6);
                    BotWebViewVibrationEffect.APP_ERROR.vibrate();
                    return;
                }
                NekoConfig.setCfApiToken(apiKey.toString().trim());
                dialog.dismiss();
            });
        }
    }

    private static Call.Factory getOkHttpClient() {
        if (okHttpClient == null) {
            if (CronetHelper.isAvailable()) {
                var builder = CronetCallFactory.newBuilder(CronetHelper.getEngine());
                builder.setCallTimeoutMillis(120 * 1000);
                builder.setReadTimeoutMillis(120 * 1000);
                builder.setWriteTimeoutMillis(120 * 1000);
                okHttpClient = builder.build();
            } else {
                var builder = new OkHttpClient.Builder();
                builder.connectTimeout(120, TimeUnit.SECONDS);
                builder.readTimeout(120, TimeUnit.SECONDS);
                builder.writeTimeout(120, TimeUnit.SECONDS);
                okHttpClient = builder.build();
            }
        }
        return okHttpClient;
    }

    private static void extractAudio(String inputFilePath, String outputFilePath) throws IOException {
        var extractor = new MediaExtractor();
        extractor.setDataSource(inputFilePath);

        MediaFormat audioFormat = null;
        int audioTrackIndex = -1;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            var format = extractor.getTrackFormat(i);
            var mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                audioFormat = format;
                audioTrackIndex = i;
                break;
            }
        }

        if (audioFormat == null) {
            throw new IOException("No audio track found in " + inputFilePath);
        }

        var muxer = new MediaMuxer(outputFilePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        var trackIndex = muxer.addTrack(audioFormat);
        muxer.start();

        extractor.selectTrack(audioTrackIndex);

        var bufferInfo = new MediaCodec.BufferInfo();
        var buffer = ByteBuffer.allocate(65536);

        while (true) {
            var sampleSize = extractor.readSampleData(buffer, 0);
            if (sampleSize < 0) {
                break;
            }

            bufferInfo.offset = 0;
            bufferInfo.size = sampleSize;
            bufferInfo.presentationTimeUs = extractor.getSampleTime();
            bufferInfo.flags = 0;

            muxer.writeSampleData(trackIndex, buffer, bufferInfo);
            extractor.advance();
        }

        muxer.stop();
        muxer.release();
        extractor.release();
    }

    public static void requestWorkersAi(String path, boolean video, BiConsumer<String, Exception> callback) {
        if (TextUtils.isEmpty(NekoConfig.cfApiToken)) {
            callback.accept(null, new Exception("Gemini API key is not set. Please set it in Settings."));
            return;
        }
        executorService.submit(() -> {
            File audioPath;
            String mimeType;
            if (video) {
                var audioFile = new File(path + ".m4a");
                try {
                    extractAudio(path, audioFile.getAbsolutePath());
                } catch (IOException e) {
                    FileLog.e(e);
                }
                audioPath = audioFile.exists() ? audioFile : new File(path);
                mimeType = "audio/mp4";
            } else {
                audioPath = new File(path);
                mimeType = "audio/ogg";
            }

            byte[] audio;
            try {
                audio = Files.readAllBytes(audioPath.toPath());
            } catch (IOException e) {
                callback.accept(null, e);
                return;
            }

            // Формируем структуру запроса к Google Gemini API
            var payload = new GeminiRequest();
            var content = new GeminiRequest.Content();
            content.parts = new ArrayList<>();

            var audioPart = new GeminiRequest.Part();
            audioPart.inlineData = new GeminiRequest.InlineData();
            audioPart.inlineData.mimeType = mimeType;
            audioPart.inlineData.data = Base64.encodeToString(audio, Base64.NO_WRAP);
            content.parts.add(audioPart);

            var textPart = new GeminiRequest.Part();
            textPart.text = "Сделай точную транскрипцию этого голосового сообщения на языке оригинала (русский или украинский). Не переводи на другие языки! Выведи только распознанный текст без кавычек, вводных слов и комментариев.";
            content.parts.add(textPart);

            payload.contents = Collections.singletonList(content);

            // Отключаем размышления и фантазии ради мгновенной скорости (1-2 сек)
            payload.generationConfig = new GeminiRequest.GenerationConfig();
            payload.generationConfig.temperature = 0.0;
            payload.generationConfig.thinkingConfig = new GeminiRequest.ThinkingConfig();
            payload.generationConfig.thinkingConfig.thinkingBudget = 0;

            var client = getOkHttpClient();
            var url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent?key=" + NekoConfig.cfApiToken;

            var request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(gson.toJson(payload), MediaType.get("application/json")));

            try (var response = client.newCall(request.build()).execute()) {
                var body = response.body().string();
                var geminiResponse = gson.fromJson(body, GeminiResponse.class);

                if (geminiResponse != null && geminiResponse.candidates != null && !geminiResponse.candidates.isEmpty()) {
                    var candidate = geminiResponse.candidates.get(0);
                    if (candidate.content != null && candidate.content.parts != null && !candidate.content.parts.isEmpty()) {
                        var transcribedText = candidate.content.parts.get(0).text;
                        callback.accept(transcribedText != null ? transcribedText.trim() : "", null);
                        return;
                    }
                }

                if (geminiResponse != null && geminiResponse.error != null) {
                    callback.accept(null, new Exception("Gemini Error: " + geminiResponse.error.message));
                } else {
                    callback.accept(null, new Exception("Failed to transcribe audio (Empty response from Gemini)"));
                }
            } catch (Exception e) {
                callback.accept(null, e);
            }
        });
    }

   // Модели данных для Google Gemini API
    public static class GeminiRequest {
        @SerializedName("contents")
        @Expose
        public List<Content> contents;

        @SerializedName("generationConfig")
        @Expose
        public GenerationConfig generationConfig;

        public static class GenerationConfig {
            @SerializedName("temperature")
            @Expose
            public Double temperature = 0.0;

            @SerializedName("thinkingConfig")
            @Expose
            public ThinkingConfig thinkingConfig;
        }

        public static class ThinkingConfig {
            @SerializedName("thinkingBudget")
            @Expose
            public Integer thinkingBudget = 0;
        }

        public static class Content {
            @SerializedName("parts")
            @Expose
            public List<Part> parts;
        }

        public static class Part {
            @SerializedName("text")
            @Expose
            public String text;

            @SerializedName("inline_data")
            @Expose
            public InlineData inlineData;
        }

        public static class InlineData {
            @SerializedName("mime_type")
            @Expose
            public String mimeType;

            @SerializedName("data")
            @Expose
            public String data;
        }
    }

    public static class GeminiResponse {
        @SerializedName("candidates")
        @Expose
        public List<Candidate> candidates;

        @SerializedName("error")
        @Expose
        public GeminiError error;

        public static class Candidate {
            @SerializedName("content")
            @Expose
            public GeminiContent content;
        }

        public static class GeminiContent {
            @SerializedName("parts")
            @Expose
            public List<GeminiPart> parts;
        }

        public static class GeminiPart {
            @SerializedName("text")
            @Expose
            public String text;
        }

        public static class GeminiError {
            @SerializedName("message")
            @Expose
            public String message;
        }
    }
}
