# Lab 1: Client-server communication with sockets

**Aim:** implement client-server communication using TCP sockets.

**Real-world link:** this is a minimal configuration server, the same role DeployGuard's controllers play. DeployGuard itself uses gRPC for agent-to-controller calls (`backend/.../rpc/Rpc.java`); this lab shows the raw socket layer underneath.

## Files
- `FlagServer.java`: multi-threaded TCP server (thread pool) that stores feature flags and bumps a version number on every change.
- `FlagClient.java`: client that sends text commands and prints replies, interactively or from command-line arguments.

## Run
```bash
javac FlagServer.java FlagClient.java
java FlagServer 5050                                  # terminal 1
java FlagClient localhost 5050                        # terminal 2 (interactive)
java FlagClient localhost 5050 "SET new-checkout true" "GET new-checkout" LIST VERSION
```

## Sample output (client)
```
OK FlagServer ready, version 0
> SET new-checkout true
  OK version 1
> GET new-checkout
  VALUE new-checkout true
> LIST
  FLAGS {new-checkout=true, search-ranker=bm25}
> VERSION
  VERSION 1
> QUIT
  BYE
```

## Points for the viva
- TCP gives a reliable, ordered byte stream; the protocol adds message boundaries with one line per request.
- The server handles each client on a pool thread, so clients do not block each other.
- Concurrent writes are safe because the flag map is a `ConcurrentHashMap` and the version is an `AtomicLong`.
- Timeouts: the client has a 3 s connect timeout and the server drops clients idle for 60 s.
