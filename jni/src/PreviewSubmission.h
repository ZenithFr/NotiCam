#pragma once

// Reset only when there is real work to submit. Never wait on a reset fence if
// submission failed: no GPU work exists that could signal it. The completion
// wait on SUCCESS must remain, because imported camera buffers are released
// when the preview call returns. Platform-free so failure ordering is testable.
template <typename Result, typename Reset, typename Submit, typename Wait>
Result completePreviewSubmission(Result success, Reset reset, Submit submit, Wait wait) {
    Result result = reset();
    if (result != success) return result;
    result = submit();
    if (result != success) return result;
    return wait();
}
