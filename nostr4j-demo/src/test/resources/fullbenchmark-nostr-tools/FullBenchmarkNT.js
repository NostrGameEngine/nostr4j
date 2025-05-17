import { useWebSocketImplementation } from 'nostr-tools/pool';
import WebSocket from 'ws';
useWebSocketImplementation(WebSocket);

import {  generateSecretKey, getPublicKey,   finalizeEvent } from 'nostr-tools';
import { SimplePool } from 'nostr-tools/pool'


// Configure benchmark
const EVENTS = 200;
const RELAY_URL = 'ws://127.0.0.1:8087';

async function runBenchmark() {
    return new Promise(  (res,rej)=>{
        const run = async () => {
            // Generate content (512 'a' characters)
            const content = 'a'.repeat(512);
            const initStarted = Date.now();

            // Create unique test ID
            const testId = `${Date.now()}-${Math.random()}`;
 
            // Generate keypair
            const privateKey = generateSecretKey();
            const publicKey = getPublicKey(privateKey);
 
            // Set up relay pool
            const writer = new SimplePool();
            const reader = new SimplePool();

            // Setup counters and timer
            let readCount = 0;
        
             console.log(`Init time:  ${Date.now() - initStarted} ms`);

            const sendStarted = Date.now();
            // Send events
            for (let i = 0; i < EVENTS; i++) {
                const event = {
                    kind: 1,
                    created_at: Math.floor(Date.now() / 1000),
                    tags: [
                        ['t', testId],
                        ['i', i.toString()]
                    ],
                    content: content.substring(0, i),
                    pubkey: publicKey
                };

                // Sign the event
                const signedEvent = finalizeEvent(event, privateKey);

                // console.log(`Sent: ${i+1}/${EVENTS}`);
                writer.publish([RELAY_URL], signedEvent);
            }
             console.log(`Send time:  ${Date.now() - sendStarted} ms`);

            const receiveStarted = Date.now();
            const sub = reader.subscribeMany([RELAY_URL], [
                {
                    kinds: [1],
                    "#t": [testId]
                }
            ], {
                onevent: (event) => {

                    readCount++;


                    if (readCount === EVENTS) {
                        console.log(`Receive time:  ${Date.now() - receiveStarted} ms`);
                        console.log(`Total time:  ${Date.now() - initStarted} ms`);
                        res();
                        sub.unsub();
                        pool.close([RELAY_URL]);

                    }
                    // console.log(`Received: ${readCount}/${EVENTS}`);
                },
            });
        };
        run();
    });
    


}

// Run the benchmark
for(let i = 0; i < 10; i++) {
    await runBenchmark();
}
