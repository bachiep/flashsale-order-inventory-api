package com.portfolio.flashsale.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.flashsale.auth.domain.Role;
import com.portfolio.flashsale.auth.domain.User;
import com.portfolio.flashsale.auth.repository.RefreshTokenRepository;
import com.portfolio.flashsale.auth.repository.UserRepository;
import com.portfolio.flashsale.auth.security.JwtService;
import com.portfolio.flashsale.catalog.repository.ProductRepository;
import com.portfolio.flashsale.common.config.CacheConfig;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CatalogIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private Clock clock;

    private String adminToken;
    private String customerToken;

    @BeforeEach
    void setUp() {
        productRepository.deleteAll();
        refreshTokenRepository.deleteAll();
        userRepository.deleteAll();
        clearCache(CacheConfig.PRODUCTS_CACHE);
        clearCache(CacheConfig.PRODUCT_BY_ID_CACHE);

        User admin = userRepository.save(new User(
                "admin-catalog@flashsale.local",
                passwordEncoder.encode("Admin@1234"),
                "Catalog Admin",
                Role.ROLE_ADMIN,
                clock.instant()
        ));
        User customer = userRepository.save(new User(
                "customer-catalog@flashsale.local",
                passwordEncoder.encode("Customer@1234"),
                "Catalog Customer",
                Role.ROLE_CUSTOMER,
                clock.instant()
        ));

        adminToken = jwtService.generateAccessToken(admin);
        customerToken = jwtService.generateAccessToken(customer);
    }

    @Test
    void enforcesAdminRbacAndPaginatedSearchFiltering() throws Exception {
        String laptopPayload = """
                {
                  "sku": "FS-LAPTOP-01",
                  "name": "Pro Ultrabook 14",
                  "description": "Flash sale laptop",
                  "category": "Electronics",
                  "price": 1299.00,
                  "initialStock": 15,
                  "active": true
                }
                """;

        mockMvc.perform(post("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + customerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(laptopPayload))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(laptopPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("FS-LAPTOP-01"))
                .andExpect(jsonPath("$.sellableQty").value(15));

        mockMvc.perform(post("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(laptopPayload))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("DUPLICATE_SKU"));

        mockMvc.perform(post("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sku": "FS-EARBUDS-02",
                                  "name": "Wireless Earbuds ANC",
                                  "description": "Noise cancelling",
                                  "category": "Audio",
                                  "price": 79.50,
                                  "initialStock": 50,
                                  "active": true
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sku": "FS-HIDDEN-03",
                                  "name": "Archived Speaker",
                                  "description": "Inactive item",
                                  "category": "Audio",
                                  "price": 120.00,
                                  "initialStock": 5,
                                  "active": false
                                }
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/products")
                        .param("category", "audio")
                        .param("activeOnly", "true")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].sku").value("FS-EARBUDS-02"));

        mockMvc.perform(get("/api/v1/products")
                        .param("q", "ultrabook")
                        .param("activeOnly", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].sku").value("FS-LAPTOP-01"));
    }

    @Test
    void cacheAsidePopulatesAndEvictsOnAdminUpdateAndInventoryGuardWorks() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/admin/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sku": "FS-MOUSE-10",
                                  "name": "Pro Wireless Mouse",
                                  "description": "8K polling",
                                  "category": "Peripherals",
                                  "price": 99.00,
                                  "initialStock": 20,
                                  "active": true
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode createdJson = objectMapper.readTree(created.getResponse().getContentAsString());
        Long productId = createdJson.get("id").asLong();
        long initialVersion = createdJson.get("inventoryVersion").asLong();

        // First read populates productById cache
        mockMvc.perform(get("/api/v1/products/{id}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(99.0));

        Cache productByIdCache = cacheManager.getCache(CacheConfig.PRODUCT_BY_ID_CACHE);
        assertThat(productByIdCache).isNotNull();
        assertThat(productByIdCache.get(productId)).isNotNull();

        // Admin update must evict productById cache
        mockMvc.perform(put("/api/v1/admin/products/{id}", productId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Pro Wireless Mouse V2",
                                  "description": "8K polling",
                                  "category": "Peripherals",
                                  "price": 85.00,
                                  "active": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price").value(85.0));

        assertThat(productByIdCache.get(productId)).isNull();

        // Next read returns fresh data and repopulates cache
        mockMvc.perform(get("/api/v1/products/{id}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Pro Wireless Mouse V2"))
                .andExpect(jsonPath("$.price").value(85.0));
        assertThat(productByIdCache.get(productId)).isNotNull();

        // Inventory adjustment succeeds with matching version and rejects stale version
        mockMvc.perform(patch("/api/v1/admin/products/{id}/inventory", productId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"availableQty\":35,\"expectedVersion\":" + initialVersion + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.availableQty").value(35));

        mockMvc.perform(patch("/api/v1/admin/products/{id}/inventory", productId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"availableQty\":40,\"expectedVersion\":" + initialVersion + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("CONCURRENT_MODIFICATION"));
    }

    private void clearCache(String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.clear();
        }
    }
}
