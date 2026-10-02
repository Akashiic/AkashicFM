package com.akashiic.fm.client;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NowPlayingTest {

    @Test
    void hostDaUrl() {
        assertEquals("stream.radioparadise.com", NowPlaying.hostOf("https://stream.radioparadise.com/mp3-128"));
        assertEquals("radio.example", NowPlaying.hostOf("http://radio.example:8000/live?x=1#y"));
        assertEquals("radio.example", NowPlaying.hostOf("http://user:senha@radio.example:8000/live"));
        assertEquals("radio.example", NowPlaying.hostOf("http://a@b@radio.example/"));
        assertEquals("[2001:db8::1]", NowPlaying.hostOf("http://[2001:db8::1]:8000/stream"));
        assertEquals("[2001:db8::1]", NowPlaying.hostOf("http://[2001:db8::1]/"));
        assertEquals("radio.example", NowPlaying.hostOf("radio.example/stream"));
        assertEquals("", NowPlaying.hostOf(""));
        assertEquals("", NowPlaying.hostOf("http://"));
    }
}
