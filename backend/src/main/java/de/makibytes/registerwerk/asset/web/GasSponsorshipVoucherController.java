package de.makibytes.registerwerk.asset.web;

import de.makibytes.registerwerk.asset.internal.GasSponsorshipVoucherService;
import de.makibytes.registerwerk.asset.internal.PaymasterVoucherDigest;
import de.makibytes.registerwerk.asset.web.dto.GasSponsorshipVoucherRequest;
import de.makibytes.registerwerk.asset.web.dto.GasSponsorshipVoucherResponse;
import de.makibytes.registerwerk.shared.SecurityUtils;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.web3j.utils.Numeric;

import static de.makibytes.registerwerk.asset.internal.PaymasterVoucherDigest.quantity;

/**
 * Customer-facing voucher issuer for {@code EwpgPaymaster}: a customer's wallet asks for a
 * signed voucher for one prepared UserOperation before submitting it to the bundler. Refusals
 * (409 no active policy / cap reached, 403 wallet or target out of scope, 400 gas out of
 * bounds) tell the UI to fall back to a self-paid transaction.
 */
@RestController
@RequestMapping("/api/v1/gas-sponsorship")
public class GasSponsorshipVoucherController {

    private final GasSponsorshipVoucherService voucherService;

    public GasSponsorshipVoucherController(GasSponsorshipVoucherService voucherService) {
        this.voucherService = voucherService;
    }

    @PostMapping("/vouchers")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<GasSponsorshipVoucherResponse> issue(
            @RequestBody @Valid GasSponsorshipVoucherRequest request,
            Authentication auth) {
        PaymasterVoucherDigest.UserOpFields op = new PaymasterVoucherDigest.UserOpFields(
            request.sender(),
            quantity(request.nonce()),
            request.initCode() == null ? new byte[0] : Numeric.hexStringToByteArray(request.initCode()),
            Numeric.hexStringToByteArray(request.callData()),
            quantity(request.verificationGasLimit()),
            quantity(request.callGasLimit()),
            quantity(request.paymasterVerificationGasLimit()),
            quantity(request.paymasterPostOpGasLimit()),
            quantity(request.preVerificationGas()),
            quantity(request.maxPriorityFeePerGas()),
            quantity(request.maxFeePerGas()));
        GasSponsorshipVoucherService.IssuedVoucher v = voucherService.issueVoucher(
            SecurityUtils.extractEntityId(auth),
            SecurityUtils.extractUserId(auth),
            SecurityUtils.primaryRole(auth, "CUSTOMER"),
            request.deploymentId(),
            op);
        return ResponseEntity.ok(new GasSponsorshipVoucherResponse(
            v.paymaster(),
            v.paymasterData(),
            Numeric.toHexStringWithPrefix(v.paymasterVerificationGasLimit()),
            Numeric.toHexStringWithPrefix(v.paymasterPostOpGasLimit()),
            v.chainId(),
            v.policyId(),
            v.validUntil(),
            v.validAfter(),
            v.maxFeePerGasCap().toString(),
            v.maxCostWei().toString()));
    }
}
