/*
 * jq_wrapper.c - Thin wrapper over jq's C library API for WASM reactor mode.
 *
 * Mirrors the flow in jq's main.c but reads/writes linear memory
 * instead of stdin/stdout.  Compiled with -mexec-model=reactor and
 * optionally pre-initialised with Wizer.
 *
 * The jq_state is created once (in a constructor) and reused.
 */

#include <stdlib.h>
#include <string.h>
#include "jv.h"
#include "jq.h"

/* ── flags (must match Java side) ───────────────────────────────── */
#define FLAG_SLURP      (1 << 0)
#define FLAG_NULL_INPUT (1 << 1)
#define FLAG_COMPACT    (1 << 2)
#define FLAG_SORT_KEYS  (1 << 3)

/* ── error return codes ─────────────────────────────────────────── */
#define RC_ERROR_INIT    -3
#define RC_ERROR_COMPILE -1

/* ── global state ───────────────────────────────────────────────── */
static jq_state *jq = NULL;

static char *output_buf = NULL;
static int   output_len = 0;
static int   output_cap = 0;

__attribute__((constructor))
static void jq_wrapper_init(void) { jq = jq_init(); }

/* ── memory helpers ─────────────────────────────────────────────── */

void *alloc(int size) { return malloc((size_t)size); }

void dealloc(void *ptr, int size) { (void)size; free(ptr); }

/* host reads the output after process() returns */
char *get_output_ptr(void) { return output_buf; }
int   get_output_len(void) { return output_len; }

/* ── growable output buffer ─────────────────────────────────────── */

static void output_reset(void) { output_len = 0; }

static int output_append(const char *s, int slen) {
    int needed = output_len + slen;
    if (needed > output_cap) {
        int new_cap = output_cap ? output_cap : 4096;
        while (new_cap < needed) new_cap *= 2;
        char *new_buf = realloc(output_buf, new_cap);
        if (!new_buf) return -1;
        output_buf = new_buf;
        output_cap = new_cap;
    }
    memcpy(output_buf + output_len, s, slen);
    output_len += slen;
    return 0;
}

/* ── buf_input: buffer-backed input, mirrors jq_util_input ──────── *
 *                                                                    *
 * Handles slurp internally (just like jq_util_input_set_parser +     *
 * jq_util_input_next_input in util.c):                               *
 *   slurp=0 → returns each parsed value immediately                  *
 *   slurp=1 → accumulates into jv_array, returns it at end of input  *
 *                                                                    *
 * Also serves as the callback for jq_set_input_cb so the `inputs`    *
 * builtin works.                                                     */

typedef struct {
    jv_parser *parser;
    jv         slurped;          /* jv_invalid() when not slurping */
} buf_input;

static void buf_input_init(buf_input *bi,
                           const char *buf, int len, int slurp) {
    bi->parser  = jv_parser_new(0);
    jv_parser_set_buf(bi->parser, buf, len, 0);
    bi->slurped = slurp ? jv_array() : jv_invalid();
}

static jv buf_input_next(buf_input *bi) {
    jv value;
    while (jv_is_valid(value = jv_parser_next(bi->parser))) {
        if (jv_is_valid(bi->slurped)) {
            bi->slurped = jv_array_append(bi->slurped, value);
            continue;
        }
        return value;
    }
    jv_free(value);

    if (jv_is_valid(bi->slurped)) {
        jv result = bi->slurped;
        bi->slurped = jv_invalid();
        return result;
    }
    return jv_invalid();
}

static jv buf_input_cb(jq_state *jq, void *data) {
    (void)jq;
    return buf_input_next((buf_input *)data);
}

static void buf_input_free(buf_input *bi) {
    jv_parser_free(bi->parser);
    if (jv_is_valid(bi->slurped))
        jv_free(bi->slurped);
}

/* ── jq_start/jq_next loop → growable output buffer ────────────── */

static int run_jq(jv input, int dumpopts) {
    jq_start(jq, input, 0);                       /* consumes input */
    jv result;
    while (jv_is_valid(result = jq_next(jq))) {
        jv dumped = jv_dump_string(result, dumpopts);  /* consumes result */
        const char *s = jv_string_value(dumped);
        int slen = jv_string_length_bytes(jv_copy(dumped));
        if (output_append(s, slen) < 0 || output_append("\n", 1) < 0) {
            jv_free(dumped);
            jv_free(jq_next(jq)); /* drain */
            return -1;
        }
        jv_free(dumped);
    }
    jv_free(result);
    return 0;
}

/* ── main entry point ───────────────────────────────────────────── *
 *                                                                    *
 * Returns bytes written to the output buffer (>= 0), or negative     *
 * error code.  Host reads output via get_output_ptr().               */

int process(
        const char *input_ptr,  int input_len,
        const char *filter_ptr, int filter_len,
        int         flags)
{
    if (!jq) return RC_ERROR_INIT;

    output_reset();

    /* null-terminate filter for jq_compile */
    char *filter = malloc(filter_len + 1);
    if (!filter) return RC_ERROR_INIT;
    memcpy(filter, filter_ptr, filter_len);
    filter[filter_len] = '\0';

    int ok = jq_compile(jq, filter);
    free(filter);
    if (!ok) return RC_ERROR_COMPILE;

    int dumpopts = (flags & FLAG_COMPACT)
        ? 0
        : JV_PRINT_INDENT_FLAGS(2);
    if (flags & FLAG_SORT_KEYS)
        dumpopts |= JV_PRINT_SORTED;

    /* set up buffer input — handles slurp internally */
    buf_input input;
    buf_input_init(&input, input_ptr, input_len, flags & FLAG_SLURP);
    jq_set_input_cb(jq, buf_input_cb, &input);

    /* two branches, same as jq main.c lines 666-693 */
    if (flags & FLAG_NULL_INPUT) {
        run_jq(jv_null(), dumpopts);
    } else {
        jv value;
        while (jv_is_valid(value = buf_input_next(&input)))
            run_jq(value, dumpopts);
    }

    jq_set_input_cb(jq, NULL, NULL);
    buf_input_free(&input);
    return output_len;
}
