#include "PreviewSubmission.h"
#include <cassert>
#include <string>

int main() {
    // Success, reset failure, submit failure, wait failure. An unsuccessful
    // submit must NEVER reach wait: the fence would never be signalled.
    for (int failure = 0; failure <= 3; ++failure) {
        std::string calls;
        int result = completePreviewSubmission(0,
            [&] { calls += 'R'; return failure == 1 ? -1 : 0; },
            [&] { calls += 'S'; return failure == 2 ? -2 : 0; },
            [&] { calls += 'W'; return failure == 3 ? -3 : 0; });
        assert(result == -failure);
        assert(calls == (failure == 1 ? "R" : failure == 2 ? "RS" : "RSW"));
    }
}
