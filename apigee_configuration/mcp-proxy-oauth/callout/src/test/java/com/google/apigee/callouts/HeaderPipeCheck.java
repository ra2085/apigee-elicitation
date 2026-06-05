package com.google.apigee.callouts;

public class HeaderPipeCheck {
    public static void main(String[] args) {
        String headersValue = "Content-Type:application/json|Accept:application/json;q=0.9,text/event-stream";
        
        System.out.println("Processing headers with PIPE separator:");
        // Regex used: \s*(?:\||\r?\n)\s*
        for (String header : headersValue.split("\\s*(?:\\||\\r?\\n)\\s*")) {
            System.out.println("Header line: [" + header + "]");
            String[] parts = header.split(":", 2);
            if (parts.length == 2) {
                System.out.println("  Key: " + parts[0].trim());
                System.out.println("  Val: " + parts[1].trim());
            }
        }
    }
}
