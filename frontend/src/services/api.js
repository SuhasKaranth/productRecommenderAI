import axios from 'axios';

const API_BASE_URL = process.env.REACT_APP_API_URL || 'http://localhost:8080';
const API_KEY = process.env.REACT_APP_API_KEY || '';

const api = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
    ...(API_KEY && { 'X-API-Key': API_KEY }),
  },
});

export const stagingApi = {
  // Get all staging products
  getAllProducts: (pendingOnly = false) =>
    api.get(`/api/admin/staging?pendingOnly=${pendingOnly}`),

  // Get single staging product
  getProduct: (id) =>
    api.get(`/api/admin/staging/${id}`),

  // Update staging product
  updateProduct: (id, data) =>
    api.put(`/api/admin/staging/${id}`, data),

  // Approve single product
  approveProduct: (id, reviewedBy, reviewNotes) =>
    api.post(`/api/admin/staging/${id}/approve`, { reviewedBy, reviewNotes }),

  // Bulk approve products
  bulkApprove: (productIds, reviewedBy, reviewNotes) =>
    api.post('/api/admin/staging/bulk-approve', { productIds, reviewedBy, reviewNotes }),

  // Reject product
  rejectProduct: (id, reviewedBy, reviewNotes) =>
    api.post(`/api/admin/staging/${id}/reject`, { reviewedBy, reviewNotes }),

  // Bulk reject products
  bulkReject: (productIds, reviewedBy, reviewNotes) =>
    api.post('/api/admin/staging/bulk-reject', { productIds, reviewedBy, reviewNotes }),

  // Delete product
  deleteProduct: (id) =>
    api.delete(`/api/admin/staging/${id}`),

  // Bulk delete products
  bulkDelete: (productIds) =>
    api.post('/api/admin/staging/bulk-delete', { productIds }),

  // Generate keywords for product
  generateKeywords: (id) =>
    api.post(`/api/admin/staging/${id}/generate-keywords`),

  // Save keywords for product
  saveKeywords: (id, keywords) =>
    api.post(`/api/admin/staging/${id}/keywords`, { keywords }),

  // Get statistics
  getStats: () =>
    api.get('/api/admin/staging/stats'),
};

export const scraperApi = {
  // Scrape URL and return text content (MVP1)
  scrapeUrl: (url) =>
    api.post('http://localhost:8081/api/scraper/scrape-url', { url }),

  // Scrape URL with AI analysis and extraction (MVP2)
  scrapeUrlEnhanced: (url) =>
    api.post('http://localhost:8081/api/scraper/scrape-url-enhanced', { url }),

  // Trigger scraping
  triggerScrape: (websiteId) =>
    api.post(`http://localhost:8081/api/scraper/trigger/${websiteId}`),

  // Get job status
  getJobStatus: (jobId) =>
    api.get(`http://localhost:8081/api/scraper/status/${jobId}`),

  // Get all sources
  getSources: () =>
    api.get('http://localhost:8081/api/scraper/sources'),

  // Get history
  getHistory: (websiteId) =>
    api.get(`http://localhost:8081/api/scraper/history/${websiteId}`),
};

// Recommendation API (Port 8080 - migrated to Spring Boot)
const recommendationApiInstance = axios.create({
  baseURL: 'http://localhost:8080',
  headers: {
    'Content-Type': 'application/json',
    ...(API_KEY && { 'X-API-Key': API_KEY }),
  },
});

export const recommendationApi = {
  // Get product recommendations based on user input
  getRecommendations: (userInput, language = 'en') =>
    recommendationApiInstance.post('/api/v1/recommend', {
      userInput: userInput,  // Changed from user_input to userInput (camelCase for Java)
      language: language,
    }),
};

export const productApi = {
  // Get all products with optional filters
  getAllProducts: (category, active, search) => {
    const params = new URLSearchParams();
    if (category) params.append('category', category);
    if (active !== undefined) params.append('active', active);
    if (search) params.append('search', search);
    return api.get(`/api/products?${params.toString()}`);
  },

  // Get single product by ID
  getProduct: (id) =>
    api.get(`/api/products/${id}`),

  // Update product
  updateProduct: (id, data) =>
    api.put(`/api/products/${id}`, data),

  // Delete single product
  deleteProduct: (id) =>
    api.delete(`/api/products/${id}`),

  // Bulk delete products
  bulkDeleteProducts: (productIds) =>
    api.post('/api/products/bulk-delete', { productIds }),

  // Generate keywords for product
  generateKeywords: (id) =>
    api.post(`/api/products/${id}/generate-keywords`),

  // Save keywords for product
  saveKeywords: (id, keywords) =>
    api.post(`/api/products/${id}/keywords`, { keywords }),

  // Generate summary for product
  generateSummary: (id) =>
    api.post(`/api/products/${id}/generate-summary`),

  // Refresh raw page content by re-scraping the product's source URL
  refreshContent: (id) =>
    api.post(`/api/products/${id}/refresh-content`),
};

export default api;
