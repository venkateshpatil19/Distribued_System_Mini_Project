# Lab 2: Coordinator election (Bully and Ring)

**Aim:** elect a coordinator among distributed processes with the Bully or the Ring algorithm.

**Real-world link:** DeployGuard's three controllers use the Bully algorithm (`backend/.../core/BullyElection.java`) so that exactly one of them runs the rollout scheduler. This lab is the stand-alone version with 5 processes.

## How it works
- Each node is a separate JVM listening on its own TCP port. Messages are single lines: `PING`, `ELECTION`, `OK`, `COORDINATOR`.
- Every node pings the leader once a second. No reply means the leader is presumed failed and an election starts.
- **Bully:** send `ELECTION` to every higher id. If nobody answers `OK`, become leader and broadcast `COORDINATOR`. A node that receives `ELECTION` answers `OK` and starts its own election, so the highest live id always wins. A recovered higher node bullies its way back.
- **Ring:** pass an `ELECTION` message clockwise to the next live node, each node appending its id. When the message returns to the starter, the highest id in the list wins, and a `COORDINATOR` message travels once round the ring.

## Run
```bash
./run-demo.sh bully        # Linux / macOS / Git Bash
./run-demo.sh ring
powershell -ExecutionPolicy Bypass -File run-demo.ps1 bully     # Windows
```
The script starts 5 nodes, kills the leader (node 5) after 8 s, restarts it 10 s later and prints the merged log. To run nodes by hand, open 5 terminals:
```bash
javac ElectionNode.java
java ElectionNode bully 1 1:5001,2:5002,3:5003,4:5004,5:5005     # change the id 1..5 in each terminal
```

## Recorded output (Bully)
```
11:14:57.844 [node 5] *** I am the LEADER ***
11:14:57.882 [node 1] new leader is 5
11:15:04.881 [node 3] leader 5 is not responding
11:15:04.881 [node 3] starts BULLY election, asking higher nodes [4, 5]
11:15:04.883 [node 4] *** I am the LEADER ***
11:15:04.884 [node 1] new leader is 4
11:15:15.689 [node 5] *** I am the LEADER ***       <- node 5 restarted and took over again
11:15:15.703 [node 1] new leader is 5
```

## Recorded output (Ring)
```
11:15:27.963 [node 3] ring complete [3, 4, 5, 1, 2] -> leader is 5
11:15:34.851 [node 3] leader 5 is not responding
11:15:34.866 [node 3] ring complete [3, 4, 1, 2] -> leader is 4     <- dead node 5 skipped
11:15:45.683 [node 5] ring complete [5, 1, 2, 3, 4] -> leader is 5
```

## Message complexity
- Bully: O(n²) messages in the worst case (the lowest node starts), O(n) in the best case.
- Ring: about 2n messages per election (n for ELECTION, n for COORDINATOR), plus the pings used to skip dead nodes.
