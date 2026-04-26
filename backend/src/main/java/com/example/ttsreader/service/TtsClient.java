package com.example.ttsreader.service;

import com.example.ttsreader.dto.TtsRequest;
import com.example.ttsreader.dto.TtsVoice;

import java.util.List;
import java.util.function.Consumer;

public interface TtsClient {

    TtsSynthesisResult synthesize(TtsRequest request);

    void streamSynthesize(TtsRequest request, Consumer<TtsStreamPiece> pieceConsumer);

    List<TtsVoice> getVoices();
}
