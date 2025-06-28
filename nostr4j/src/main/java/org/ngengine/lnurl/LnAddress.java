package org.ngengine.lnurl;

import java.net.URI;
import java.net.URISyntaxException;

// lud16
public class LnAddress extends LnUrl {
    private final String lnAddress;

    public LnAddress(String lnAddress) throws   URISyntaxException  {
        super(addressToUrl(lnAddress));
        this.lnAddress = lnAddress.trim();
    }



    private static URI addressToUrl(String addr) throws URISyntaxException {
        if (addr == null || addr.isEmpty()) {
            throw new URISyntaxException(addr, "Lightning address cannot be null or empty", 0);
        }

        // Split the address into username and domain parts
        String[] parts = addr.split("@");
        if (parts.length != 2) {
            throw new URISyntaxException(addr, "Invalid lightning address format", 0);
        }

        String username = parts[0];
        String domain = parts[1];

        // Validate username - only a-z0-9-_.+ are allowed
        if (!username.matches("^[a-z0-9\\-_.+]+$")) {
            throw new URISyntaxException(addr, "Invalid username format. Only a-z0-9-_.+ characters are allowed.", 0);
        }

        // Determine if it's a clearnet or onion domain
        String scheme = domain.endsWith(".onion") ? "http" : "https";

        // Construct the URL
        String url = scheme + "://" + domain + "/.well-known/lnurlp/" + username;

        return new URI(url);
    }

    @Override
    public String toString() {
        return lnAddress;
    }


}
