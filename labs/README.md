# Laboratory assignments

Each lab is plain Java (standard library only, Java 11 or newer) and compiles with `javac` without Maven, except the Hadoop job in Lab 5. Every program was run in the build environment; recorded output is in each lab's README.

| Lab | Topic | Folder | Run |
|---|---|---|---|
| 1 | Client-server communication with sockets | `lab1-client-server` | `java FlagServer 5050` and `java FlagClient localhost 5050` |
| 2 | Bully and Ring coordinator election (5 separate processes) | `lab2-election` | `./run-demo.sh bully` or `run-demo.ps1 ring` |
| 3 | Deadlock detection: wait-for graph and Chandy-Misra-Haas | `lab3-deadlock` | `java DeadlockDetection` |
| 4 | Lamport and vector clocks, Berkeley synchronization | `lab4-clocks` | `java LogicalClocks`, `java Berkeley` |
| 5 | MapReduce word count (Hadoop job plus no-Hadoop version) | `lab5-wordcount` | `hadoop jar ...` or `java LocalWordCount input 2` |
| 6 | Distributed application: DeployGuard | `lab6-deployguard` | root `README.md` and `scripts/demo.ps1` |
| Self-study | Lodha-Kshemkalyani fair mutual exclusion vs Ricart-Agrawala | `extra-fair-mutex` | `java FairMutex` |

Each lab README states the aim, the real-world link to DeployGuard, run steps, recorded output and viva points.
