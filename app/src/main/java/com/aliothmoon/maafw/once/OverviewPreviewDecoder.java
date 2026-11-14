package com.aliothmoon.maafw.once;

/** Receives color only from the same original PNG that the existing audit verifies. */
final class OverviewPreviewDecoder implements PngImageAudit.Decoder {
    private final PngImageAudit.Decoder decoder;
    private final OverviewFramePreview preview;
    private final int width, height;
    private boolean decoded;
    OverviewPreviewDecoder(PngImageAudit.Decoder decoder, OverviewFramePreview preview, int width, int height) {
        this.decoder = java.util.Objects.requireNonNull(decoder);
        this.preview = java.util.Objects.requireNonNull(preview); this.width = width; this.height = height;
    }
    @Override public synchronized PngImageAudit.Image decode(byte[] png, int length) throws Exception {
        if (decoded) throw new IllegalStateException("One original PNG decode required"); decoded = true;
        PngImageAudit.Image image = decoder.decode(png, length);
        if (image == null) return null;
        return new PngImageAudit.Image() {
            @Override public PngImageAudit.Facts facts() throws Exception { return image.facts(); }
            @Override public void argbRow(int y, int[] row) throws Exception {
                image.argbRow(y, row);
                var facts = image.facts();
                if (facts.width() != width || facts.height() != height || facts.config() != PngImageAudit.Config.ARGB_8888)
                    throw new IllegalStateException("Exact overview color shape required");
                preview.row(y, row); if (y == height - 1) preview.complete();
            }
            @Override public void erase() throws Exception { image.erase(); }
            @Override public void recycle() throws Exception { image.recycle(); }
        };
    }
}
