import { chacha20 as _chacha20 } from '@noble/ciphers';
import { schnorr as _schnorr, secp256k1 as _secp256k1 } from '@noble/curves/secp256k1';
import { hmac as _hmac, sha256 as _sha256 } from '@noble/hashes';
import { extract as _hkdf_extract, expand as _hkdf_expand } from '@noble/hashes/hkdf'
import { base64 as _base64 } from '@scure/base';
 
export const randomBytes = (length /*int*/) => { // Uint8Array (byte[])
    return _schnorr.utils.randomBytes(length);
};

export const generatePrivateKey = () => { // Uint8Array (byte[])
    return _schnorr.utils.randomPrivateKey(); 
};

export const genPubKey = (secKey) => {// Uint8Array (byte[])
    return _schnorr.getPublicKey(secKey); 
};

 export const sha256 = (data /*byte[]*/) => { // Uint8Array (byte[])
     return _sha256(new Uint8Array(data));
};

export const toJSON = (obj /*obj*/) => { // str
   return JSON.stringify(obj); 
};

export const fromJSON = (json/*str*/) => {
    return JSON.parse(json); // obj
} ;

export const sign = (data /*byte[]*/, privKeyBytes  /*byte[]*/) => {  // Uint8Array (byte[])
    return _schnorr.sign(data, privKeyBytes);
};

export const verify = (data /*byte[]*/, pub /*byte[]*/, sig/*byte[]*/) => { // Uint8Array (byte[])
    return _schnorr.verify(sig, data, pub);
};


export const secp256k1SharedSecret = (privKey /*byte[]*/, pubKey /*byte[]*/) => { // Uint8Array (byte[])
    return _secp256k1.getSharedSecret(privKey, pubKey); 
};

export const hmac = (key /*byte[]*/, data1 /*byte[]*/, data2 /*byte[]*/) => { // Uint8Array (byte[])
    const msg = new Uint8Array([...data1, ...data2]);
    return _hmac(sha256, key, msg);
};

export const hkdf_extract = (salt /*byte[]*/, ikm /*byte[]*/) => { // Uint8Array (byte[])
    return _hkdf_extract(sha256, ikm, salt);
};

export const hkdf_expand = (prk/*byte[]*/, info/*byte[]*/, length/*int*/) => { // Uint8Array (byte[])
    return _hkdf_expand(sha256, prk, info, length);
};

export const base64encode = (data /*byte[]*/) => { //str
    return _base64.encode(new Uint8Array(data));
};

export const base64decode = (data /*str*/) => { // Uint8Array (byte[])
    return _base64.decode(data);
};

export const chacha20 = (key/*byte[]*/, nonce/*byte[]*/, data/*byte[]*/) => { // Uint8Array (byte[])
    return _chacha20(key, nonce, data);
};
 
export const setTimeout = (callback, delay) => { //void
    return setTimeout(callback, delay);
}


// Expose functions on the global object so TeaVM can call them.
window.nostr4j_jsBinds = {
    randomBytes,
    generatePrivateKey,
    genPubKey,
    sha256,
    toJSON,
    fromJSON,
    sign,
    verify,
    secp256k1SharedSecret,
    hmac,
    hkdf_extract,
    hkdf_expand,
    base64encode,
    base64decode,
    chacha20,
    setTimeout
};