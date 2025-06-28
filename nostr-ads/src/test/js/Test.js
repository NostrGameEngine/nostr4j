// const nostr4j  = require('../../../build/generated/teavm/js/nostr4j.js');
import Binds from './TeaVMBinds.js';
import { WebSocket } from 'ws';
global.WebSocket = WebSocket;

global.nostr4j_jsBinds = Binds;
import { NostrPool, NostrRelay, NostrFilter, init as nostr4jinit } from '../../../build/generated/teavm/js/nostr4j.js';

async function main(){
    
    nostr4jinit();

    const pool = new NostrPool(null);
    pool.connectRelay(new NostrRelay("wss://relay.damus.io"));
    pool.addNoticeListener((relay,msg,error) => {
        if (error) {
            console.error(`Error from ${relay.url}:`, error);
        } else {
            console.log(`Notice from ${relay.url}:`, msg);
        }
    });
    const filter = new NostrFilter();
    filter.withKind(1);
    filter.limit(3)
    const sub = pool.subscribe(filter);
    sub.addCloseListener(reason => {
        console.log("Subscription closed: " + reason);
    });

    sub.addEventListener((event, stored) => {
        console.log("Event received: " + event.id);
        if (stored) {
            console.log("Stored in relay: " + stored);
        }

    });

    sub.addEoseListener(all => {
        console.log("End of stream received: " + all);
        if (all) {
            console.log("All relays have sent EOSE");
        } else {
            console.log("Not all relays sent EOSE");
        }
    });

 sub.open();
    console.log("pl?");
    

}

main();