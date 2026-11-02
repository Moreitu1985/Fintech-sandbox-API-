package io.ledgerforge.webhook;

import io.ledgerforge.common.error.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.Locale;

/**
 * Guards against server-side request forgery: a tenant must not be able to make LedgerForge send requests
 * to its own network (databases, the cloud metadata service, admin ports). Checked when an endpoint is
 * registered and again before every delivery, because DNS answers can change.
 *
 * <p>Allowed: https, port 443, no user info, a host whose every address is public. Refused: loopback,
 * private, link-local (incl. 169.254.169.254), carrier-grade NAT, unique-local IPv6, multicast, reserved
 * and documentation ranges, and IPv4-mapped/compatible forms of any of them.
 */
@Component
public class WebhookUrlPolicy {

    public static final String CODE = "WEBHOOK_URL_NOT_ALLOWED";
    private static final String HOST_REFUSED = "host must resolve to public internet addresses only";

    private final boolean allowInsecure;
    private final Resolver resolver;

    /** Name resolution, replaceable in tests. */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private static final ExecutorService DNS = Executors.newVirtualThreadPerTaskExecutor();
    private static final Duration DNS_TIMEOUT = Duration.ofSeconds(2);

    @Autowired
    public WebhookUrlPolicy(WebhookProperties properties) {
        this(properties.allowInsecureTargets(), WebhookUrlPolicy::resolveWithTimeout);
    }

    /** A name server that never answers must not hold a request (or a send slot) for long. */
    static InetAddress[] resolveWithTimeout(String host) throws UnknownHostException {
        Future<InetAddress[]> lookup = DNS.submit(() -> InetAddress.getAllByName(host));
        try {
            return lookup.get(DNS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            lookup.cancel(true);
            throw new UnknownHostException("lookup timed out");
        } catch (ExecutionException e) {
            throw new UnknownHostException("lookup failed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnknownHostException("interrupted");
        }
    }

    public WebhookUrlPolicy(boolean allowInsecure, Resolver resolver) {
        this.allowInsecure = allowInsecure;
        this.resolver = resolver;
    }

    /** Validates a URL a tenant wants to register and returns it normalised. */
    public URI validate(String url) {
        URI uri = parse(url);
        checkTarget(uri);
        return uri;
    }

    /** Throws {@link ApiException} with code {@value #CODE} if the URL may not be called right now. */
    public void checkTarget(URI uri) {
        if (allowInsecure) {
            return;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw refused("must use https");
        }
        if (uri.getPort() != -1 && uri.getPort() != 443) {
            throw refused("must use the standard HTTPS port (443)");
        }
        // One message whether the name doesn't resolve or resolves somewhere private: a tenant must not be
        // able to probe which internal names exist.
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(hostOf(uri));
        } catch (UnknownHostException e) {
            throw refused(HOST_REFUSED);
        }
        if (addresses.length == 0) {
            throw refused(HOST_REFUSED);
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw refused(HOST_REFUSED);
            }
        }
    }

    static URI parse(String url) {
        if (url == null || url.isBlank() || url.length() > 2048) {
            throw refused("must be an absolute URL of at most 2048 characters");
        }
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (URISyntaxException e) {
            throw refused("is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!uri.isAbsolute() || uri.getHost() == null || !(scheme.equals("https") || scheme.equals("http"))) {
            throw refused("must be an absolute http(s) URL with a host");
        }
        if (uri.getRawUserInfo() != null) {
            throw refused("must not contain a user name or password");
        }
        if (uri.getRawFragment() != null) {
            throw refused("must not contain a fragment");
        }
        if (!uri.getScheme().equals(scheme)) {
            uri = URI.create(scheme + uri.toString().substring(uri.getScheme().length())); // HTTPS:// -> https://
        }
        return uri.normalize();
    }

    private static String hostOf(URI uri) {
        String host = uri.getHost();
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            return isPublicV4(b);
        }
        if (address instanceof Inet6Address) {
            // IPv4-mapped (::ffff:a.b.c.d) and IPv4-compatible (::a.b.c.d): judge the embedded IPv4 address.
            boolean firstTenZero = true;
            for (int i = 0; i < 10; i++) {
                firstTenZero &= b[i] == 0;
            }
            if (firstTenZero && ((b[10] == (byte) 0xff && b[11] == (byte) 0xff) || (b[10] == 0 && b[11] == 0))) {
                return isPublicV4(new byte[] {b[12], b[13], b[14], b[15]});
            }
            int first = b[0] & 0xff;
            if ((first & 0xfe) == 0xfc) {          // fc00::/7 unique local (includes AWS fd00:ec2::254)
                return false;
            }
            if (first == 0x20 && (b[1] & 0xff) == 0x01 && (b[2] & 0xff) == 0x0d && (b[3] & 0xff) == 0xb8) {
                return false;                       // 2001:db8::/32 documentation
            }
            if (first == 0x20 && (b[1] & 0xff) == 0x01 && b[2] == 0 && b[3] == 0) {
                return false;                       // 2001::/32 Teredo: tunnels to an embedded IPv4 address
            }
            if (first == 0x20 && (b[1] & 0xff) == 0x02) {
                return false;                       // 2002::/16 6to4: tunnels to an embedded IPv4 address
            }
            if (first == 0x00 || first == 0xff) {   // ::/8 reserved, ff00::/8 multicast
                return false;
            }
            return (first & 0xe0) == 0x20;          // only global unicast 2000::/3
        }
        return false;
    }

    private static boolean isPublicV4(byte[] b) {
        int a = b[0] & 0xff;
        int c = b[1] & 0xff;
        return !(a == 0                              // 0.0.0.0/8 "this network"
                || a == 10                           // 10/8 private
                || a == 127                          // loopback
                || (a == 100 && c >= 64 && c <= 127) // 100.64/10 carrier-grade NAT
                || (a == 169 && c == 254)            // link-local, cloud metadata
                || (a == 172 && c >= 16 && c <= 31)  // 172.16/12 private
                || (a == 192 && c == 0 && (b[2] & 0xff) == 0)  // 192.0.0/24 protocol assignments
                || (a == 192 && c == 0 && (b[2] & 0xff) == 2)  // 192.0.2/24 documentation
                || (a == 192 && c == 168)            // private
                || (a == 198 && (c == 18 || c == 19))          // 198.18/15 benchmarking
                || (a == 198 && c == 51 && (b[2] & 0xff) == 100) // documentation
                || (a == 203 && c == 0 && (b[2] & 0xff) == 113)  // documentation
                || a >= 224);                        // multicast, reserved, broadcast
    }

    private static ApiException refused(String why) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, CODE, "Webhook URL " + why + ".");
    }
}
