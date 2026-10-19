package dev.prototype.core.conversion;

/** Messages are safe for the requesting player: rejected authorization exposes no registry IDs. */
public record ConversionResult(boolean success, String message) {
    public static ConversionResult rejected(String message) { return new ConversionResult(false, message); }
    public static ConversionResult converted() { return new ConversionResult(true, "Sword converted."); }
}
