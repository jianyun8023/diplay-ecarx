#ifndef DIPLAY_SPEEX_ECHO_H
#define DIPLAY_SPEEX_ECHO_H
#include <stddef.h>
#include <stdint.h>

typedef struct DiPlaySpeex DiPlaySpeex;
DiPlaySpeex *diplay_speex_create(int frame, int filter, int rate, int suppress, int suppress_active);
int diplay_speex_process(DiPlaySpeex *c, uint8_t *frame, size_t length,
                        const int16_t *reference, size_t reference_samples);
void diplay_speex_destroy(DiPlaySpeex *c);
#endif
