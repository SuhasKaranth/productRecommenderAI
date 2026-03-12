import React, { useState, useEffect } from 'react';
import {
  Box,
  Card,
  CardContent,
  Typography,
  TextField,
  Button,
  Table,
  TableBody,
  TableCell,
  TableContainer,
  TableHead,
  TableRow,
  Paper,
  Chip,
  IconButton,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Grid,
  FormControl,
  InputLabel,
  Select,
  MenuItem,
  CircularProgress,
  Alert,
  Checkbox,
  Snackbar,
} from '@mui/material';
import {
  Edit as EditIcon,
  Refresh as RefreshIcon,
  CheckCircle,
  Cancel,
  Delete as DeleteIcon,
} from '@mui/icons-material';
import { productApi } from '../services/api';
import ProductEditDrawer from '../components/ProductEditDrawer';

const AllProducts = () => {
  const [products, setProducts] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  const [filters, setFilters] = useState({
    category: '',
    active: '',
    search: '',
  });
  const [editDialogOpen, setEditDialogOpen] = useState(false);
  const [editingProduct, setEditingProduct] = useState(null);
  const [selectedProducts, setSelectedProducts] = useState([]);
  const [deleteConfirmOpen, setDeleteConfirmOpen] = useState(false);
  const [drawerProductId, setDrawerProductId] = useState(null);
  const [successSnackbarOpen, setSuccessSnackbarOpen] = useState(false);

  useEffect(() => {
    fetchProducts();
  }, []);

  const fetchProducts = async () => {
    setLoading(true);
    setError(null);

    try {
      const response = await productApi.getAllProducts(
        filters.category || undefined,
        filters.active !== '' ? filters.active === 'true' : undefined,
        filters.search || undefined
      );
      setProducts(response.data);
    } catch (err) {
      setError(err.response?.data?.message || err.message || 'Failed to fetch products');
    } finally {
      setLoading(false);
    }
  };

  const handleFilterChange = (field, value) => {
    setFilters({ ...filters, [field]: value });
  };

  const handleApplyFilters = () => {
    fetchProducts();
  };

  const handleClearFilters = () => {
    setFilters({ category: '', active: '', search: '' });
    setTimeout(() => fetchProducts(), 100);
  };

  const handleEdit = (product) => {
    setEditingProduct({ ...product });
    setEditDialogOpen(true);
  };

  const handleCloseEdit = () => {
    setEditDialogOpen(false);
    setEditingProduct(null);
  };

  const handleSaveEdit = async () => {
    try {
      await productApi.updateProduct(editingProduct.id, editingProduct);
      setEditDialogOpen(false);
      setEditingProduct(null);
      fetchProducts();
    } catch (err) {
      setError(err.response?.data?.message || err.message || 'Failed to update product');
    }
  };

  const handleEditFieldChange = (field, value) => {
    setEditingProduct({ ...editingProduct, [field]: value });
  };

  const handleSelectAll = (event) => {
    if (event.target.checked) {
      setSelectedProducts(products.map((p) => p.id));
    } else {
      setSelectedProducts([]);
    }
  };

  const handleSelectProduct = (productId) => {
    if (selectedProducts.includes(productId)) {
      setSelectedProducts(selectedProducts.filter((id) => id !== productId));
    } else {
      setSelectedProducts([...selectedProducts, productId]);
    }
  };

  const handleDeleteSelected = () => {
    setDeleteConfirmOpen(true);
  };

  const handleConfirmDelete = async () => {
    try {
      await productApi.bulkDeleteProducts(selectedProducts);
      setDeleteConfirmOpen(false);
      setSelectedProducts([]);
      fetchProducts();
    } catch (err) {
      setError(err.response?.data?.message || err.message || 'Failed to delete products');
      setDeleteConfirmOpen(false);
    }
  };

  const handleCancelDelete = () => {
    setDeleteConfirmOpen(false);
  };

  const handleRowClick = (productId) => setDrawerProductId(productId);
  const handleDrawerClose = () => setDrawerProductId(null);
  const handleDrawerSaveSuccess = () => {
    setDrawerProductId(null);
    setSuccessSnackbarOpen(true);
    fetchProducts();
  };

  const categories =['CASA', 'CREDIT_CARD', 'FINANCING', 'INVESTMENT', 'INSURANCE', 'CAR', 'HOME', 'BUSINESS'];

  return (
    <Box>
      <Box display="flex" justifyContent="space-between" alignItems="center" mb={3}>
        <Typography variant="h4">All Products</Typography>
        <Box display="flex" gap={2}>
          {selectedProducts.length > 0 && (
            <Button
              variant="contained"
              color="error"
              startIcon={<DeleteIcon />}
              onClick={handleDeleteSelected}
            >
              Delete Selected ({selectedProducts.length})
            </Button>
          )}
          <Button
            variant="outlined"
            startIcon={<RefreshIcon />}
            onClick={fetchProducts}
            disabled={loading}
          >
            Refresh
          </Button>
        </Box>
      </Box>

      {/* Filters Card */}
      <Card sx={{ mb: 3 }}>
        <CardContent>
          <Typography variant="h6" gutterBottom>
            Filters
          </Typography>
          <Grid container spacing={2} alignItems="center">
            <Grid item xs={12} md={3}>
              <FormControl fullWidth>
                <InputLabel>Category</InputLabel>
                <Select
                  value={filters.category}
                  onChange={(e) => handleFilterChange('category', e.target.value)}
                  label="Category"
                >
                  <MenuItem value="">All Categories</MenuItem>
                  {categories.map((cat) => (
                    <MenuItem key={cat} value={cat}>
                      {cat}
                    </MenuItem>
                  ))}
                </Select>
              </FormControl>
            </Grid>

            <Grid item xs={12} md={3}>
              <FormControl fullWidth>
                <InputLabel>Status</InputLabel>
                <Select
                  value={filters.active}
                  onChange={(e) => handleFilterChange('active', e.target.value)}
                  label="Status"
                >
                  <MenuItem value="">All</MenuItem>
                  <MenuItem value="true">Active</MenuItem>
                  <MenuItem value="false">Inactive</MenuItem>
                </Select>
              </FormControl>
            </Grid>

            <Grid item xs={12} md={4}>
              <TextField
                fullWidth
                label="Search"
                placeholder="Product name, code, or category..."
                value={filters.search}
                onChange={(e) => handleFilterChange('search', e.target.value)}
                onKeyPress={(e) => e.key === 'Enter' && handleApplyFilters()}
              />
            </Grid>

            <Grid item xs={12} md={2}>
              <Box display="flex" gap={1}>
                <Button
                  variant="contained"
                  onClick={handleApplyFilters}
                  disabled={loading}
                  fullWidth
                >
                  Apply
                </Button>
                <Button
                  variant="outlined"
                  onClick={handleClearFilters}
                  disabled={loading}
                  fullWidth
                >
                  Clear
                </Button>
              </Box>
            </Grid>
          </Grid>
        </CardContent>
      </Card>

      {/* Error Alert */}
      {error && (
        <Alert severity="error" onClose={() => setError(null)} sx={{ mb: 2 }}>
          {error}
        </Alert>
      )}

      {/* Products Table */}
      <Card>
        <CardContent>
          <Box display="flex" justifyContent="space-between" alignItems="center" mb={2}>
            <Typography variant="h6">Products ({products.length})</Typography>
          </Box>

          {loading ? (
            <Box display="flex" justifyContent="center" p={4}>
              <CircularProgress />
            </Box>
          ) : products.length === 0 ? (
            <Alert severity="info">No products found</Alert>
          ) : (
            <TableContainer component={Paper}>
              <Table>
                <TableHead>
                  <TableRow>
                    <TableCell padding="checkbox">
                      <Checkbox
                        indeterminate={
                          selectedProducts.length > 0 &&
                          selectedProducts.length < products.length
                        }
                        checked={
                          products.length > 0 &&
                          selectedProducts.length === products.length
                        }
                        onChange={handleSelectAll}
                      />
                    </TableCell>
                    <TableCell><strong>Product Code</strong></TableCell>
                    <TableCell><strong>Product Name</strong></TableCell>
                    <TableCell><strong>Category</strong></TableCell>
                    <TableCell><strong>Keywords</strong></TableCell>
                    <TableCell><strong>Islamic Structure</strong></TableCell>
                    <TableCell align="right"><strong>Annual Fee (AED)</strong></TableCell>
                    <TableCell align="right"><strong>Min Income (AED)</strong></TableCell>
                    <TableCell align="center"><strong>Status</strong></TableCell>
                    <TableCell><strong>Created At</strong></TableCell>
                    <TableCell align="center"><strong>Actions</strong></TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {products.map((product) => (
                    <TableRow
                      key={product.id}
                      hover
                      selected={selectedProducts.includes(product.id)}
                      onClick={() => handleRowClick(product.id)}
                      sx={{ cursor: 'pointer' }}
                    >
                      <TableCell padding="checkbox">
                        <Checkbox
                          checked={selectedProducts.includes(product.id)}
                          onChange={(e) => { e.stopPropagation(); handleSelectProduct(product.id); }}
                          onClick={(e) => e.stopPropagation()}
                        />
                      </TableCell>
                      <TableCell>
                        <Typography variant="body2" fontFamily="monospace">
                          {product.productCode}
                        </Typography>
                      </TableCell>
                      <TableCell>
                        <Typography variant="body2" fontWeight="medium">
                          {product.productName}
                        </Typography>
                        {product.subCategory && (
                          <Typography variant="caption" color="textSecondary">
                            {product.subCategory}
                          </Typography>
                        )}
                      </TableCell>
                      <TableCell>
                        <Chip
                          label={product.category}
                          size="small"
                          color="primary"
                          variant="outlined"
                        />
                      </TableCell>
                      <TableCell>
                        <Box display="flex" flexWrap="wrap" gap={0.5}>
                          {product.keywords && product.keywords.length > 0
                            ? product.keywords.slice(0, 3).map((kw, i) => (
                                <Chip key={i} label={kw} size="small" variant="outlined" sx={{ mr: 0.5 }} />
                              ))
                            : <Chip label="No keywords" size="small" color="warning" variant="outlined" />
                          }
                        </Box>
                      </TableCell>
                      <TableCell>
                        <Typography variant="body2">
                          {product.islamicStructure || 'N/A'}
                        </Typography>
                      </TableCell>
                      <TableCell align="right">
                        <Typography variant="body2">
                          {product.annualFee !== null && product.annualFee !== undefined
                            ? product.annualFee.toFixed(2)
                            : 'N/A'}
                        </Typography>
                      </TableCell>
                      <TableCell align="right">
                        <Typography variant="body2">
                          {product.minIncome !== null && product.minIncome !== undefined
                            ? product.minIncome.toFixed(2)
                            : 'N/A'}
                        </Typography>
                      </TableCell>
                      <TableCell align="center">
                        {product.active ? (
                          <Chip
                            icon={<CheckCircle />}
                            label="Active"
                            size="small"
                            color="success"
                          />
                        ) : (
                          <Chip
                            icon={<Cancel />}
                            label="Inactive"
                            size="small"
                            color="default"
                          />
                        )}
                      </TableCell>
                      <TableCell>
                        <Typography variant="caption">
                          {product.createdAt
                            ? new Date(product.createdAt).toLocaleDateString()
                            : 'N/A'}
                        </Typography>
                      </TableCell>
                      <TableCell align="center">
                        <IconButton
                          size="small"
                          onClick={(e) => { e.stopPropagation(); handleEdit(product); }}
                          color="primary"
                          title="Edit Product"
                        >
                          <EditIcon fontSize="small" />
                        </IconButton>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </TableContainer>
          )}
        </CardContent>
      </Card>

      {/* Edit Dialog */}
      <Dialog
        open={editDialogOpen}
        onClose={handleCloseEdit}
        maxWidth="md"
        fullWidth
      >
        <DialogTitle>Edit Product</DialogTitle>
        <DialogContent>
          {editingProduct && (
            <Box sx={{ mt: 2 }}>
              <Grid container spacing={2}>
                <Grid item xs={12} md={6}>
                  <TextField
                    fullWidth
                    label="Product Code"
                    value={editingProduct.productCode}
                    disabled
                  />
                </Grid>
                <Grid item xs={12} md={6}>
                  <TextField
                    fullWidth
                    label="Product Name"
                    value={editingProduct.productName}
                    onChange={(e) => handleEditFieldChange('productName', e.target.value)}
                  />
                </Grid>
                <Grid item xs={12} md={6}>
                  <FormControl fullWidth>
                    <InputLabel>Category</InputLabel>
                    <Select
                      value={editingProduct.category}
                      onChange={(e) => handleEditFieldChange('category', e.target.value)}
                      label="Category"
                    >
                      {categories.map((cat) => (
                        <MenuItem key={cat} value={cat}>
                          {cat}
                        </MenuItem>
                      ))}
                    </Select>
                  </FormControl>
                </Grid>
                <Grid item xs={12} md={6}>
                  <TextField
                    fullWidth
                    label="Sub Category"
                    value={editingProduct.subCategory || ''}
                    onChange={(e) => handleEditFieldChange('subCategory', e.target.value)}
                  />
                </Grid>
                <Grid item xs={12}>
                  <TextField
                    fullWidth
                    multiline
                    rows={3}
                    label="Description"
                    value={editingProduct.description || ''}
                    onChange={(e) => handleEditFieldChange('description', e.target.value)}
                  />
                </Grid>
                <Grid item xs={12} md={6}>
                  <TextField
                    fullWidth
                    label="Islamic Structure"
                    value={editingProduct.islamicStructure || ''}
                    onChange={(e) => handleEditFieldChange('islamicStructure', e.target.value)}
                  />
                </Grid>
                <Grid item xs={12} md={6}>
                  <FormControl fullWidth>
                    <InputLabel>Status</InputLabel>
                    <Select
                      value={editingProduct.active}
                      onChange={(e) => handleEditFieldChange('active', e.target.value)}
                      label="Status"
                    >
                      <MenuItem value={true}>Active</MenuItem>
                      <MenuItem value={false}>Inactive</MenuItem>
                    </Select>
                  </FormControl>
                </Grid>
                <Grid item xs={12} md={6}>
                  <TextField
                    fullWidth
                    type="number"
                    label="Annual Fee (AED)"
                    value={editingProduct.annualFee || ''}
                    onChange={(e) =>
                      handleEditFieldChange('annualFee', parseFloat(e.target.value) || null)
                    }
                  />
                </Grid>
                <Grid item xs={12} md={6}>
                  <TextField
                    fullWidth
                    type="number"
                    label="Min Income (AED)"
                    value={editingProduct.minIncome || ''}
                    onChange={(e) =>
                      handleEditFieldChange('minIncome', parseFloat(e.target.value) || null)
                    }
                  />
                </Grid>
              </Grid>
            </Box>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={handleCloseEdit}>Cancel</Button>
          <Button onClick={handleSaveEdit} variant="contained" color="primary">
            Save Changes
          </Button>
        </DialogActions>
      </Dialog>

      {/* Delete Confirmation Dialog */}
      <Dialog open={deleteConfirmOpen} onClose={handleCancelDelete}>
        <DialogTitle>Confirm Delete</DialogTitle>
        <DialogContent>
          <Typography>
            Are you sure you want to delete {selectedProducts.length} product(s)? This action cannot
            be undone.
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={handleCancelDelete}>Cancel</Button>
          <Button onClick={handleConfirmDelete} variant="contained" color="error">
            Delete
          </Button>
        </DialogActions>
      </Dialog>

      <ProductEditDrawer
        productId={drawerProductId}
        onClose={handleDrawerClose}
        onSaveSuccess={handleDrawerSaveSuccess}
      />
      <Snackbar
        open={successSnackbarOpen}
        autoHideDuration={4000}
        onClose={() => setSuccessSnackbarOpen(false)}
        message="Product updated"
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      />
    </Box>
  );
};

export default AllProducts;
