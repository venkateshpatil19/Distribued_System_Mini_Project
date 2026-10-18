# Self-study: Lodha and Kshemkalyani's fair mutual exclusion

**Topic (Unit III self-study):** Lodha-Kshemkalyani fair distributed mutual exclusion, compared with Ricart-Agrawala.

Both algorithms grant the critical section in request-priority order (sequence number, then site id), so they are fair. Lodha-Kshemkalyani saves messages when requests are concurrent:
- A site that receives a concurrent REQUEST while it is also requesting does not send a REPLY; the REQUEST itself serves as the reply.
- Each site keeps a local request queue (LRQ) of concurrent requests and a vector RV of whom it has heard from.
- A site enters when its own request heads the LRQ and RV is full.
- On exit it sends one FLUSH to the next concurrent request, instead of REPLY messages to all of them. FLUSH and REPLY both carry the executed request id, and receivers drop every LRQ entry up to it.

Message cost per entry: Ricart-Agrawala always needs 2(N-1). Lodha-Kshemkalyani needs between N-1 and 2(N-1): 2N - |CSet| when a lower-priority concurrent request exists, 2N - 1 - |CSet| otherwise, where CSet is the set of concurrent requests.

## Run
```bash
javac FairMutex.java
java FairMutex
```
Five sites are threads; channels are FIFO with a random 1-8 ms delay. The program checks that no two sites are ever in the critical section together and that every request is served.

## Recorded output
```
5 sites, 6 rounds of simultaneous requests each, FIFO channels with 1-8 ms delay
Ricart-Agrawala      entries=30  messages=240  messages/entry=8.00  mutual exclusion held
Lodha-Kshemkalyani   entries=30  messages=144  messages/entry=4.80  mutual exclusion held
First LK entries in order: [(1,0), (1,1), (1,2), (1,3), (1,4), (2,0), (2,1), (2,2), (2,3), (2,4)]
LK used 40% fewer messages than RA (RA needs 2(N-1) = 8 per entry).
PASS: both safe and all requests served
```
With all 5 requests concurrent, four sites pay 2N - |CSet| = 5 messages and the lowest-priority site pays N - 1 = 4, an average of 4.8, matching the measured value.

## Assumptions
Reliable FIFO channels, no site failures, one outstanding request per site. Rules follow A. Kshemkalyani's lecture notes on distributed mutual exclusion (cs.uic.edu/~ajayk/ext/mex.pdf) and Lodha and Kshemkalyani, "A Fair Distributed Mutual Exclusion Algorithm", IEEE TPDS 11(6), 2000.

## Relation to DeployGuard
The product protects rollout mutations with leases and fencing tokens granted by the leader controller (`backend/.../controller/Leases.java`), a coordinator-based scheme that tolerates crashes. This simulation covers the permission-based family taught in the syllabus.
