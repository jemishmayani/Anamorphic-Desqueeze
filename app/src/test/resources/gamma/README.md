# Gamma test fixtures

Tiny (64×36, 1 s) files generated with ffmpeg for `GammaClassifierTest`. They exercise header
metadata only; picture-based cases use synthetic frames in the test itself.

The D-Log / D-Log M fixtures are **synthetic**: real DJI files that were checked didn't record their
profile name, so these model a camera that writes it in a gamma metadata key or a camera XML field.
