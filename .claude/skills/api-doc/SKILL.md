---
name: api-doc
description: Generate or update Swagger (SpringDoc OpenAPI) annotations for Java Spring Boot controllers. Use when asked to write API docs, add Swagger annotations, or document REST endpoints.
allowed-tools: Read, Grep, Glob
---

# Swagger (SpringDoc OpenAPI) Auto-Documentation Guide

## Workflow
1. Read the target Controller file to identify endpoints
2. Also read related Request/Response DTO files
3. Add or supplement annotations following the rules below
4. If existing annotations exist, don't overwrite — only fill in missing parts

## Annotation Rules

### Controller Class
```java
@Tag(name = "Order", description = "Order creation, retrieval, and cancellation API")
@RestController
public class OrderController { }
```

### Each Endpoint
```java
@Operation(
    summary = "Create order",
    description = "Creates an order given a product and quantity. Returns 400 if stock is insufficient."
)
@ApiResponses({
    @ApiResponse(responseCode = "201", description = "Order created successfully",
        content = @Content(schema = @Schema(implementation = OrderResponse.class))),
    @ApiResponse(responseCode = "400", description = "Insufficient stock or invalid request",
        content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "401", description = "Authentication failed"),
    @ApiResponse(responseCode = "500", description = "Server error")
})
@PostMapping("/orders")
public ResponseEntity<OrderResponse> createOrder(@RequestBody @Valid OrderRequest request) { }
```

### Path Variable / Request Param
```java
@Parameter(description = "Order ID", example = "1001")
@PathVariable Long orderId;
```

### DTO Fields
```java
public class OrderRequest {
    @Schema(description = "Product ID", example = "42")
    private Long productId;

    @Schema(description = "Order quantity", example = "3", minimum = "1")
    private int quantity;
}
```

## Rules
- Keep summary concise (within 15 characters)
- description should include business context and key exception scenarios
- Use actually valid values for examples
- Always include 401 response for endpoints requiring authentication
- Use the project's common ErrorResponse class for error responses
- Do not use hidden = true (if documentation exclusion is needed, add a comment with the reason)

## Important
- Never modify existing business logic code
- Add annotation imports without omission
- If both Controller and DTO need changes, handle them together