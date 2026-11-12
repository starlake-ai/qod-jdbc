# End-to-end run against Keycloak

Not part of `mvn verify`. Needs Docker, Node, Google Chrome and a QoD manager jar.

1. `./keycloak-setup.sh`
2. `npm install playwright@1.49.1` in this folder.
3. Boot a throwaway manager with Keycloak as its data-plane provider, in a scratch HOME so your
   own qod profile is untouched:

   ```
   HOME=$PWD/home QOD_CONFIG_FILE=$PWD/home/config.toml \
   QOD_MGMT_PUBLIC_BASE_URL=http://localhost:20900 QOD_AUTH_KEYCLOAK_ENABLED=true \
   QOD_AUTH_KEYCLOAK_BASE_URL=http://localhost:8180 QOD_AUTH_KEYCLOAK_REALM=qod \
   QOD_AUTH_KEYCLOAK_CLIENT_ID=qod-flightsql QOD_AUTH_KEYCLOAK_CLIENT_SECRET=qod-flightsql-secret \
   qod serve --demo --jar <manager jar> --pg-port 25499
   ```

4. `javac -cp ../target/qod-jdbc-*.jar -d out src/ai/starlake/qod/jdbc/E2e.java`, then
   `java -De2e.dir=$PWD -cp out:../target/qod-jdbc-0.1.0-SNAPSHOT.jar ai.starlake.qod.jdbc.E2e acme alice demo-alice-kc`
   (the data-plane provider: tenant `acme` signs in with passwords).
5. Switch `acme` to its own SSO as a superuser (`POST /api/tenant/setAuth` with
   `authProvider=keycloak`, `issuerUrl=http://localhost:8180/realms/qod`, `baseUrl`, `realm=qod`,
   `clientId=acme-console`, `clientSecret=acme-console-secret`) and run step 4 again, then with
   `mallory mallory-pw` (refused by Flight), and `node console.mjs` (a console SSO login).

`E2e` sits in the driver's package to hand the sign-in URL to Playwright (`drive.mjs`) instead
of the desktop browser; the rest is the shipped code path.
