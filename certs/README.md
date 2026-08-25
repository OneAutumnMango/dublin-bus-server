Drop extra root CA certificates here (`*.crt` or `*.pem`) when building behind a TLS
inspecting proxy such as Zscaler. They are installed into both the OS trust store and
the JVM truststore, in the build and runtime stages.

Leave this directory empty on a normal network - the build works without it.

On a machine that already trusts the proxy, copy the certs across with:

    cp /usr/local/share/ca-certificates/*.crt certs/

Certificates here are gitignored; only this note and .gitkeep are tracked.
