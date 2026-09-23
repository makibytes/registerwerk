package de.makibytes.registerwerk.blockchain.internal;

import org.web3j.abi.EventEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * ABI of the {@code EwpgERC7540} request-lifecycle events, and their decoding from raw logs —
 * shared by {@link VaultRequestIngestionService} (log scan) and {@link VaultConfirmationListener}
 * (fulfil receipt reconciliation) so both read the same on-chain truth the same way.
 */
final class Erc7540Events {

    static final Event DEPOSIT_REQUESTED = new Event("DepositRequested", List.of(
            new TypeReference<Uint256>(true) {}, new TypeReference<Address>(true) {},
            new TypeReference<Address>(true) {}, new TypeReference<Uint256>(false) {}));
    static final Event REDEEM_REQUESTED = new Event("RedeemRequested", List.of(
            new TypeReference<Uint256>(true) {}, new TypeReference<Address>(true) {},
            new TypeReference<Address>(true) {}, new TypeReference<Uint256>(false) {}));
    /** data: assets, shares, navAtFulfill. */
    static final Event DEPOSIT_FULFILLED = new Event("DepositRequestFulfilled", List.of(
            new TypeReference<Uint256>(true) {}, new TypeReference<Uint256>(false) {},
            new TypeReference<Uint256>(false) {}, new TypeReference<Uint256>(false) {}));
    /** data: shares, assets, navAtFulfill — note the order differs from the deposit event. */
    static final Event REDEEM_FULFILLED = new Event("RedeemRequestFulfilled", List.of(
            new TypeReference<Uint256>(true) {}, new TypeReference<Uint256>(false) {},
            new TypeReference<Uint256>(false) {}, new TypeReference<Uint256>(false) {}));
    static final Event REQUEST_CANCELLED = new Event("RequestCancelled", List.of(
            new TypeReference<Uint256>(true) {}, new TypeReference<Address>(true) {}));
    static final Event FORCED_REQUEST_CANCELLED = new Event("ForcedRequestCancelled", List.of(
            new TypeReference<Uint256>(true) {}, new TypeReference<Address>(true) {},
            new TypeReference<Utf8String>(false) {}));

    static final String DEPOSIT_REQUESTED_TOPIC = EventEncoder.encode(DEPOSIT_REQUESTED);
    static final String REDEEM_REQUESTED_TOPIC = EventEncoder.encode(REDEEM_REQUESTED);
    static final String DEPOSIT_FULFILLED_TOPIC = EventEncoder.encode(DEPOSIT_FULFILLED);
    static final String REDEEM_FULFILLED_TOPIC = EventEncoder.encode(REDEEM_FULFILLED);
    static final String REQUEST_CANCELLED_TOPIC = EventEncoder.encode(REQUEST_CANCELLED);
    static final String FORCED_REQUEST_CANCELLED_TOPIC = EventEncoder.encode(FORCED_REQUEST_CANCELLED);

    static final List<String> ALL_TOPICS = List.of(
            DEPOSIT_REQUESTED_TOPIC, REDEEM_REQUESTED_TOPIC, DEPOSIT_FULFILLED_TOPIC,
            REDEEM_FULFILLED_TOPIC, REQUEST_CANCELLED_TOPIC, FORCED_REQUEST_CANCELLED_TOPIC);

    /** On-chain NAV is 1e18 fixed-point (see {@code Erc4626AdminService#strikeNav}). */
    private static final int NAV_DECIMALS = 18;

    private Erc7540Events() {}

    /** Executed values of a {@code *RequestFulfilled} event. */
    record Fulfilment(BigInteger requestId, BigInteger assets, BigInteger shares, BigDecimal navPerShare) {}

    static String topic0(Log log) {
        return log.getTopics() == null || log.getTopics().isEmpty()
                ? null : log.getTopics().get(0).toLowerCase(Locale.ROOT);
    }

    static BigInteger indexedUint(Log log, int topicIndex) {
        return Numeric.toBigInt(log.getTopics().get(topicIndex));
    }

    static String indexedAddress(Log log, int topicIndex) {
        String topic = log.getTopics().get(topicIndex);
        return "0x" + topic.substring(topic.length() - 40).toLowerCase(Locale.ROOT);
    }

    /** Decodes the non-indexed data of {@code event} from {@code log}. */
    @SuppressWarnings("rawtypes")
    static List<Type> data(Log log, Event event) {
        return FunctionReturnDecoder.decode(log.getData(), event.getNonIndexedParameters());
    }

    /** The {@code *RequestFulfilled} event for {@code requestId} in {@code log}, if it is one. */
    static Optional<Fulfilment> fulfilment(Log log, BigInteger requestId) {
        String topic = topic0(log);
        boolean deposit = DEPOSIT_FULFILLED_TOPIC.equals(topic);
        boolean redeem = REDEEM_FULFILLED_TOPIC.equals(topic);
        if (!deposit && !redeem || log.getTopics().size() < 2 || !indexedUint(log, 1).equals(requestId)) {
            return Optional.empty();
        }
        var values = data(log, deposit ? DEPOSIT_FULFILLED : REDEEM_FULFILLED);
        BigInteger first = (BigInteger) values.get(0).getValue();
        BigInteger second = (BigInteger) values.get(1).getValue();
        BigInteger nav = (BigInteger) values.get(2).getValue();
        BigInteger assets = deposit ? first : second;
        BigInteger shares = deposit ? second : first;
        return Optional.of(new Fulfilment(requestId, assets, shares, new BigDecimal(nav, NAV_DECIMALS)));
    }
}
