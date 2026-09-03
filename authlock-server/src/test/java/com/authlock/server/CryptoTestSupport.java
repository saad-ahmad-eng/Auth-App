package com.authlock.server;

import com.authlock.common.FileContent;
import com.authlock.common.VaultService;
import com.authlock.common.VaultServiceException;
import com.authlock.common.crypto.AesGcmCipher;

import javax.crypto.SecretKey;
import java.rmi.RemoteException;

/**
 * Test-only helper encrypting/decrypting around {@code uploadFile}/
 * {@code downloadFile}, matching what a real client must now do since
 * Implementation Phase 6 made AES-GCM transport encryption mandatory
 * (Security.md §7). Centralizes the crypto boilerplate so test methods
 * stay focused on the behavior they're actually verifying.
 */
final class CryptoTestSupport {

    private static final AesGcmCipher CIPHER = new AesGcmCipher();

    private CryptoTestSupport() {
    }

    static String uploadPlaintext(VaultService client, SecretKey key, String sessionToken,
            String filename, byte[] plaintext) throws RemoteException, VaultServiceException {
        AesGcmCipher.Encrypted encrypted = CIPHER.encrypt(key, plaintext);
        return client.uploadFile(sessionToken, filename, encrypted.ciphertext(), encrypted.iv());
    }

    static byte[] downloadPlaintext(VaultService client, SecretKey key, String sessionToken, String fileId)
            throws RemoteException, VaultServiceException {
        FileContent content = client.downloadFile(sessionToken, fileId);
        return CIPHER.decrypt(key, content.iv(), content.fileBytes());
    }
}
