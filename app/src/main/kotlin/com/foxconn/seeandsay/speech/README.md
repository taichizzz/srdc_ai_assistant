# Speech module

This package owns Week 1 voice input and output. Phase 2 defines the
provider-neutral `SttClient` and immutable `SttResult`. Later M1.1 phases add the
Google-backed `CloudSttClient` and `MicRecorder` in their assigned phases.

The package must expose only Kotlin/coroutine contracts. Google Cloud gRPC and
protobuf types remain private to `CloudSttClient`, and this package must never
import the future Accessibility bridge or decision modules.

Phase 2 intentionally contains contracts only: no audio capture, provider, or
network implementation exists yet.
