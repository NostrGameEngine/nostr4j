package org.ngengine.wallets.nip47;

public class NWCException extends RuntimeException {

    private static final long serialVersionUID = 1L;
    private String code;
    private String message;

    public NWCException(String code, String message) {
        super(code+": " + message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
  
}
