#include "speex_echo.h"
#include <stdint.h>
#include <stdlib.h>

#include "speex/speex_echo.h"
#include "speex/speex_preprocess.h"

struct DiPlaySpeex {
    SpeexEchoState *echo;
    SpeexPreprocessState *preprocess;
    spx_int16_t *mic;
    spx_int16_t *out;
    int frame;
};

void diplay_speex_destroy(DiPlaySpeex *c) {
    if (c == NULL) return;
    if (c->preprocess != NULL) speex_preprocess_state_destroy(c->preprocess);
    if (c->echo != NULL) speex_echo_state_destroy(c->echo);
    free(c->mic);
    free(c->out);
    free(c);
}

DiPlaySpeex *diplay_speex_create(int frame, int filter, int rate, int suppress, int suppressActive) {
    if (frame <= 0 || filter < frame || rate <= 0) return 0;
    DiPlaySpeex *c = calloc(1, sizeof(*c));
    if (c == NULL) return 0;
    c->frame = frame;
    c->echo = speex_echo_state_init(frame, filter);
    c->preprocess = speex_preprocess_state_init(frame, rate);
    c->mic = calloc((size_t) frame, sizeof(*c->mic));
    c->out = calloc((size_t) frame, sizeof(*c->out));
    if (c->echo == NULL || c->preprocess == NULL || c->mic == NULL || c->out == NULL) {
        diplay_speex_destroy(c);
        return 0;
    }
    spx_int32_t value = rate;
    speex_echo_ctl(c->echo, SPEEX_ECHO_SET_SAMPLING_RATE, &value);
    // The recorder's own noise suppression is disabled while this runs (it would distort the echo the
    // filter models), so denoise here, after cancelling. Gain stays with the platform.
    value = 1;
    speex_preprocess_ctl(c->preprocess, SPEEX_PREPROCESS_SET_DENOISE, &value);
    value = 0;
    speex_preprocess_ctl(c->preprocess, SPEEX_PREPROCESS_SET_AGC, &value);
    speex_preprocess_ctl(c->preprocess, SPEEX_PREPROCESS_SET_VAD, &value);
    speex_preprocess_ctl(c->preprocess, SPEEX_PREPROCESS_SET_DEREVERB, &value);
    speex_preprocess_ctl(c->preprocess, SPEEX_PREPROCESS_SET_ECHO_STATE, c->echo);
    value = suppress;
    speex_preprocess_ctl(c->preprocess, SPEEX_PREPROCESS_SET_ECHO_SUPPRESS, &value);
    value = suppressActive;
    speex_preprocess_ctl(c->preprocess, SPEEX_PREPROCESS_SET_ECHO_SUPPRESS_ACTIVE, &value);
    return c;
}

int diplay_speex_process(DiPlaySpeex *c, uint8_t *bytes, size_t length,
                        const int16_t *reference, size_t reference_samples) {
    if (c == NULL || bytes == NULL || reference == NULL ||
        length / 2 < (size_t) c->frame || reference_samples < (size_t) c->frame) return 0;
    for (int i = 0; i < c->frame; ++i) {
        c->mic[i] = (spx_int16_t) ((uint16_t) (uint8_t) bytes[2 * i] |
                                 ((uint16_t) (uint8_t) bytes[2 * i + 1] << 8));
    }
    speex_echo_cancellation(c->echo, c->mic, reference, c->out);
    speex_preprocess_run(c->preprocess, c->out);
    for (int i = 0; i < c->frame; ++i) {
        uint16_t sample = (uint16_t) c->out[i];
        bytes[2 * i] = (uint8_t) sample;
        bytes[2 * i + 1] = (uint8_t) (sample >> 8);
    }
    return 1;
}
