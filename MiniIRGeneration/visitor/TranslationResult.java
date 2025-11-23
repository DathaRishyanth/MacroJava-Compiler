package visitor;

/**
 * A helper class to store the result of translating a MiniIR expression.
 * It contains the generated MicroIR code and the identifier (TEMP, literal, or label)
 * that holds the final result of the expression.
 */
class TranslationResult {
    String code;
    String resultIdentifier;

    public TranslationResult(String code, String resultIdentifier) {
        this.code = code;
        this.resultIdentifier = resultIdentifier;
    }

    @Override
    public String toString() {
        // This is helpful for debugging but should not be relied on by the visitor logic.
        return this.code + this.resultIdentifier;
    }
}