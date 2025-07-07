package org.ngengine.nostrads.client.services.display;

import java.util.List;

import org.ngengine.nostrads.protocol.AdBidEvent;
import org.ngengine.nostrads.protocol.types.AdTaxonomy;
import org.ngengine.nostrads.protocol.types.AdTaxonomy.Term;

final class RankedBid  {
    final AdBidEvent bid;
    private final Adspace adspace;
    private int impressionCount;
    private Double score;
    private double penalty = 0;

    RankedBid(
        AdBidEvent bid,
        Adspace adspace
    ){
        this.bid = bid;
        this.adspace = adspace;
        this.impressionCount = 0;
    }


    void markImpression() {
        this.impressionCount++;
        score = null;
    }

    void setPenalty(int penalty) {
        this.penalty = penalty;
        score = null; // Reset score since penalty affects it
    }

    private double aspectRatioRatio(double aspect1, double aspect2) {
        // Ensure the ratio is >= 1 by dividing the larger by the smaller
        if (aspect1 > aspect2) {
            return aspect1 / aspect2;
        } else {
            return aspect2 / aspect1;
        }
    }

    Double getScore() {
        if (score != null) {
            return score;
        }
        double spaceAspect = adspace.getRatio();
        List<AdTaxonomy.Term> spaceCategories = adspace.getCategories() != null ? adspace.getCategories() : List.of();

        // Extract aspect ratio from bid
        double bidAspect = bid.getAspectRatio().getFloatValue();

        // Calculate how different the aspects are (closer to 0 is better)
        double aspectDiff = Math.abs(aspectRatioRatio(bidAspect, spaceAspect) - 1.0);

        // If aspect ratios are too different, reject the bid
        if (aspectDiff > 0.4) {
            return Double.MIN_VALUE;
        }

        // Score for aspect ratio: 1.0 when identical, decreasing as they differ
        double aspectScore = Math.max(0, 1.0 - aspectDiff);

        // Category matching boost
        List<AdTaxonomy.Term> bidCategories = bid.getCategories();
        boolean categoryMatch = bidCategories.stream()
                .anyMatch(c -> spaceCategories.contains(c));
        double categoryScore = categoryMatch ? 1.2 : 1.0;

        // Price score using logarithmic scale to dampen large variations
        double priceScore = Math.log(bid.getBidMsats() + 1);

        // Apply impression penalty (existing from markImpression method)
        double impressionFactor = Math.max(0.7, Math.pow(0.9, impressionCount));

        // Calculate base score
        double baseScore = priceScore * aspectScore * categoryScore * impressionFactor;

        // Apply penalty factor - higher penalty means lower score
        score = penalty > 0 ? baseScore / (1.0 + penalty) : baseScore;

        return score;

    }
}