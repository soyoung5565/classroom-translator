package com.classroom.translator.audio;

/** Java bridge: Kotlin metadata hides OfflineQwen3AsrModelConfig, so construct via Java. */
public class Qwen3AsrBridge {
    public static com.k2fsa.sherpa.onnx.OfflineRecognizer create(
            String convFrontend, String encoder, String decoder, String tokenizer,
            int threads, int maxNewTokens) {
        com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig q =
                new com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig(
                        convFrontend, encoder, decoder, tokenizer,
                        2000, maxNewTokens, 0.0f, 1.0f, 0, "");
        com.k2fsa.sherpa.onnx.OfflineModelConfig m = new com.k2fsa.sherpa.onnx.OfflineModelConfig();
        m.setQwen3Asr(q);
        m.setModelType("qwen3_asr");
        m.setNumThreads(threads);
        com.k2fsa.sherpa.onnx.OfflineRecognizerConfig c =
                new com.k2fsa.sherpa.onnx.OfflineRecognizerConfig();
        c.setModelConfig(m);
        return new com.k2fsa.sherpa.onnx.OfflineRecognizer(null, c);
    }
}
