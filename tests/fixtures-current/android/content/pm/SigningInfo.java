package android.content.pm;
public final class SigningInfo {
    private final Signature[] signers;
    public SigningInfo(Signature... signers) { this.signers = signers.clone(); }
    public Signature[] getApkContentsSigners() { return signers.clone(); }
}
