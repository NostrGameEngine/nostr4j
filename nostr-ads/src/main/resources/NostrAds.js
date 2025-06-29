import * as _Binds from './nostr-ads.js';

console.log(_Binds);
const { NostrAds, newAdsKey } = _Binds;


const newAdvertiserClient = function (relays, appKey, adsKey) {
    const ads = new NostrAds(
        relays,
        appKey,
        adsKey
    );
    return {
        close: () => ads.close(),
        publishNewBid: async (bid) => {
            return new Promise((resolve, reject) => {
                ads.publishNewBid(bid, (ev, error) => {
                    if (!error) {
                        resolve(ev);
                    } else {
                        reject(error);
                    }
                });
            });
        },
        handleBid: async ({
            bidEvent,
            listeners
        }) => {
            return ads.handleBid(bid, listeners);
        }

    };
};

const newOffererClient = function (relays, appKey, adsKey) {
    const ads = new NostrAds(
        relays,
        appKey,
        adsKey
    );

    return {
        close: () => ads.close(),
        handleOffers: async ({
            filters,
            listeners
        }) => {
            return ads.handleOffers(filters, listeners);
        }

    };
}


export default {
    newAdvertiserClient,
    newOffererClient,
    newAdsKey
};