package de.makibytes.registerwerk.blockchain.web.dto;

/**
 * Everything the frontends' {@code FheClientService} needs to read + userDecrypt a confidential
 * balance directly against Zama's relayer, client-side: which contract, on which chain. Every
 * other input to {@code createInstance} (ACL/KMS/InputVerifier addresses, relayer URL) comes from
 * the {@code @zama-fhe/relayer-sdk} package's own bundled {@code SepoliaConfig} preset — this
 * backend does not re-serve those constants.
 */
public record ConfidentialContextResponse(String contractAddress, String chain, String network, Long chainId) {}
