# qod-jdbc

JDBC driver for [Quack on Demand](https://github.com/starlake-ai/quack-on-demand). It is the
Apache Arrow Flight SQL JDBC driver, unchanged, plus `authenticator=externalbrowser`: a browser
sign-in at your tenant's identity provider, as Snowflake's driver does.

```
jdbc:qod://qod.example.com:31338?tenant=acme&pool=bi&authenticator=externalbrowser
```

Opening a connection opens your browser on the QoD manager, which sends you to your identity
provider. Once you are signed in, the tab says you can close it and the connection opens. The
token stays in the JVM until shortly before it expires, so later connections (DBeaver opens
several) do not open the browser again. Nothing is written to disk.

## Properties

| Property | Default | Meaning |
|---|---|---|
| `authenticator` | `default` | `default`: `user`/`password` or `token`, as with the Arrow driver. `externalbrowser`: browser sign-in. |
| `managerUrl` | `http://<host>:20900` | The QoD manager as your browser reaches it (behind an ingress: its public URL). |
| `browserTimeoutSec` | `120` | How long to wait for the sign-in. |
| `tenant`, `pool` | | Sent to QoD as with the Arrow driver. |

Every other property (`useEncryption`, `disableCertificateVerification`, `catalog`, ...) goes to
the Arrow Flight SQL driver as is. The URL port defaults to `31338`, QoD's FlightSQL port.

## How the sign-in works

1. The driver makes an ephemeral P-256 key pair and a random state, and listens on
   `127.0.0.1` on a free port.
2. It opens `<managerUrl>/api/auth/driver/start?port=..&key=..&state=..&tenant=..`.
3. The manager signs these into the OAuth state and redirects to the tenant's identity provider
   (its console sign-in client), or to the manager's data-plane provider for a tenant without
   one. The identity provider needs no new redirect URI.
4. After the sign-in the manager redirects the browser to `http://127.0.0.1:<port>/qod/callback`
   with the id_token encrypted to the driver's key (JWE, ECDH-ES and A256GCM).
5. The driver decrypts it and connects with it as the Arrow driver's `token`.

A failure reaches the driver at once: `oidc_not_configured` means neither the tenant nor the
manager has an identity provider to sign in with, so use `user`/`password`.

The manager must be QoD 0.13.0 or later, with `QOD_MGMT_PUBLIC_BASE_URL` set to the URL the
identity provider redirects back to.

## Versions

The driver's version is the version of the Arrow Flight SQL JDBC driver it embeds: qod-jdbc
19.0.0 is Arrow's 19.0.0 plus the QoD authenticators, and `getMajorVersion` /
`getMinorVersion` answer Arrow's. Moving to another Arrow release is one change, the
`<version>` in `pom.xml`, which the Arrow dependency reads.

## Build

```
mvn verify
```

Java 11 or later. The jar in `target/` is self-contained (it bundles the Arrow driver). On
Java 25 the driver sets `org.apache.arrow.driver.jdbc.shaded.io.netty.noUnsafe=false` before the
Arrow driver loads, unless the application set it, since the Netty the Arrow driver bundles
turns `Unsafe` off there while its allocator needs it.

## Releasing

```
./release.sh --dry-run   # every check and the build, nothing tagged or published
./release.sh             # tag v<version>, push it, publish a GitHub release with the jar
```

The version is `pom.xml`'s. The script refuses a snapshot version, a branch other than `main`,
a working tree with changes, a `HEAD` that is not `origin/main`, a tag that already exists, and
a `gh` that is not signed in. It then runs `mvn clean verify`, tags `v<version>`, pushes the tag
and publishes the jar and its SHA-256 as a GitHub release.

## License

Apache License 2.0.
