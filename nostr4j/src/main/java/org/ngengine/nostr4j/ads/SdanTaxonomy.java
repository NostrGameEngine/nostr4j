package org.ngengine.nostr4j.ads;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class SdanTaxonomy implements Serializable{
    public static record Term (
            String id,
            String parent,
            String name,
            String tier1Name,
            String tier2Name,
            String tier3Name,
            String tier4Name,
            String path,
            String extension) implements Serializable {      
        @Override
        public String toString(){
            return path;
        }
    }

    private static record TreeNode(
        Term taxonomy,
        Map<String, TreeNode> children
    ) implements Serializable {}


    private final Map<String, TreeNode> taxonomyFlat = new HashMap<>();
    private final TreeNode taxonomyTree = new TreeNode(null,  new HashMap<>());

    public SdanTaxonomy(InputStream csvIn) throws IOException{
        loadCSV(csvIn);
    }

    public SdanTaxonomy() throws IOException{
        InputStream is = this.getClass().getResourceAsStream("nostr-content-taxonomy.csv");
        BufferedInputStream bis = new BufferedInputStream(is);
        loadCSV(bis);
        bis.close();       
    }

    private void loadCSV(InputStream csvIn) throws IOException {
        byte[] bdata= csvIn.readAllBytes();
        String data = new String(bdata, StandardCharsets.UTF_8);
        StringTokenizer tokenizer = new StringTokenizer(data, "\n");
        
        if(tokenizer.hasMoreTokens()) tokenizer.nextToken();

        while (tokenizer.hasMoreTokens()) {
            String line = tokenizer.nextToken().trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue; // Skip empty lines and comments
            }
            String[] parts = line.split(",", -1);
            if (parts.length < 8) throw new IllegalArgumentException("Invalid CSV format: " + line+ " "+ parts.length + " parts found, expected 8");
            
            String id = parts[0].trim();
            String parent = parts[1].trim();
            String name = parts[2].trim();
            String tier1 = parts[3].trim();
            String tier2 = parts[4].trim();
            String tier3 = parts[5].trim();
            String tier4 = parts[6].trim();
            String extension = parts[7].trim();


            Term taxonomy = new Term(
                id, 
                parent, 
                name, 
                tier1, 
                tier2, 
                tier3, 
                tier4, 
                Stream.of(tier1, tier2, tier3, tier4).filter(tier -> tier != null && !tier.isEmpty()).collect(Collectors.joining("/")), 
                extension
            );
            
            TreeNode parentNode = !parent.isEmpty() ? taxonomyFlat.get(parent) : null;
            if(parentNode==null){
                parentNode = taxonomyTree; // Use root if no parent found
            }

            TreeNode node = new TreeNode(taxonomy,  new HashMap<>());
            taxonomyFlat.put(id, node);
            parentNode.children.put(id, node);
        }
    }

    public Map<String, TreeNode> getTree(){
        return taxonomyTree.children;
    }


    public Term getByPath(String term) {
        for (TreeNode node : taxonomyFlat.values()) {
            if (node.taxonomy.path().equalsIgnoreCase(term)) {
                return node.taxonomy;
            }
        }
        return null; 
    }

    public Term getById(String id){
        TreeNode node = taxonomyFlat.get(id);
        if (node != null) {
            return node.taxonomy;
        }
        return null; 
    }
    

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SdanTaxonomy)) return false;
        SdanTaxonomy that = (SdanTaxonomy) o;
        return taxonomyFlat.equals(that.taxonomyFlat);
    }

    @Override
    public int hashCode() {
        return taxonomyFlat.hashCode();
    }


    private String toString(TreeNode node, String indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent).append(node.taxonomy.name).append(" (").append(node.taxonomy.id).append(")\n");
        for (TreeNode child : node.children.values()) {
            sb.append(toString(child, indent + "  "));
        }
        return sb.toString();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("NostrContentTaxonomy:\n");
        for (TreeNode child : taxonomyTree.children.values()) {
            sb.append(toString(child, "  "));
        }

        return sb.toString();
    }


}
