package org.ngengine.nostr4j.sdan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Test;
import org.ngengine.nostr4j.ads.SdanTaxonomy;
import org.ngengine.nostr4j.ads.SdanTaxonomy.Term;

public class TestTaxonomy {
    @Test
    public void testLoadAndSerializeTaxonomy() throws IOException, ClassNotFoundException {
        SdanTaxonomy taxonomy = new SdanTaxonomy();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(bos);
        oos.writeObject(taxonomy);

        byte[] serializedData = bos.toByteArray();
        oos.close();

        ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(serializedData));
        SdanTaxonomy deserialized = (SdanTaxonomy) ois.readObject();
        ois.close();

        assertEquals(taxonomy,deserialized);       
    }


    @Test
    public void testGetTaxonomy() throws IOException {
        SdanTaxonomy taxonomy = new SdanTaxonomy();

        // by id
        Term found = taxonomy.getById("215");
        assertEquals("215", found.id());
        assertEquals("Food & Drink/Barbecues and Grilling", found.path());
        assertEquals("Food & Drink", found.tier1Name());
        assertEquals("Barbecues and Grilling", found.tier2Name());
        assertEquals("", found.tier3Name());
        assertEquals("", found.tier4Name());
        assertEquals("Barbecues and Grilling", found.name());
        assertEquals("", found.extension());

        // by term
        Term foundByTerm = taxonomy.getByPath("Food & Drink/Barbecues and Grilling");
        assertEquals(found, foundByTerm);
        assertEquals("215", foundByTerm.id());
        assertEquals("Food & Drink/Barbecues and Grilling", foundByTerm.path());
        assertEquals("Food & Drink", foundByTerm.tier1Name());
        assertEquals("Barbecues and Grilling", foundByTerm.tier2Name());
        assertEquals("", foundByTerm.tier3Name());
        assertEquals("", foundByTerm.tier4Name());
        assertEquals("Barbecues and Grilling", foundByTerm.name());
        assertEquals("", foundByTerm.extension());

        // not found
        Term notFound = taxonomy.getById("9999");
        assertTrue(notFound == null);

    }

    @Test
    public void testEquals() throws IOException{
        SdanTaxonomy taxonomy = new SdanTaxonomy();

        Term found = taxonomy.getById("215");
        assertEquals("215", found.id());
        assertEquals("Food & Drink/Barbecues and Grilling", found.path());
        assertEquals("Food & Drink", found.tier1Name());
        assertEquals("Barbecues and Grilling", found.tier2Name());
        assertEquals("", found.tier3Name());
        assertEquals("", found.tier4Name());
        assertEquals("Barbecues and Grilling", found.name());
        assertEquals("", found.extension());

        Term newTaxonomy = new Term(
            found.id(),
            found.parent(),
            found.name(), 
            found.tier1Name(),
            found.tier2Name(),
            found.tier3Name(),
            found.tier4Name(),
                Stream.of(found
                        .tier1Name(), 
                        found.tier2Name(), 
                        found.tier3Name(), 
                        found.tier4Name()).filter(tier -> tier != null && !tier.isEmpty()).collect(Collectors.joining("/")), 

            found.extension()
        );

        assertEquals(found, newTaxonomy);
    }
}
