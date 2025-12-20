import React, { useState, useEffect } from 'react';
import {
  Box,
  Card,
  CardContent,
  Typography,
  Button,
  Chip,
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  TextField,
  Checkbox,
  CircularProgress,
  Alert,
  IconButton,
} from '@mui/material';
import {
  CheckCircle,
  Cancel,
  Edit,
  Delete,
  Refresh,
  Psychology,
  Add,
  Close,
} from '@mui/icons-material';
import { stagingApi } from '../services/api';

const ProductEditDialog = ({ open, product, onClose, onSave }) => {
  const [formData, setFormData] = useState(product || {});

  useEffect(() => {
    if (product) setFormData(product);
  }, [product]);

  const handleSave = async () => {
    try {
      await stagingApi.updateProduct(formData.id, formData);
      onSave();
    } catch (error) {
      console.error('Failed to update product:', error);
    }
  };

  return (
    <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>Edit Product</DialogTitle>
      <DialogContent>
        <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, mt: 2 }}>
          <TextField
            label="Product Name"
            value={formData.productName || ''}
            onChange={(e) => setFormData({ ...formData, productName: e.target.value })}
            fullWidth
          />
          <TextField
            label="Category"
            value={formData.category || ''}
            onChange={(e) => setFormData({ ...formData, category: e.target.value })}
            fullWidth
          />
          <TextField
            label="Description"
            value={formData.description || ''}
            onChange={(e) => setFormData({ ...formData, description: e.target.value })}
            multiline
            rows={4}
            fullWidth
          />
          <TextField
            label="Islamic Structure"
            value={formData.islamicStructure || ''}
            onChange={(e) => setFormData({ ...formData, islamicStructure: e.target.value })}
            fullWidth
          />
          <TextField
            label="Annual Rate (%)"
            type="number"
            value={formData.annualRate || ''}
            onChange={(e) => setFormData({ ...formData, annualRate: e.target.value })}
            fullWidth
          />
        </Box>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>Cancel</Button>
        <Button onClick={handleSave} variant="contained">
          Save Changes
        </Button>
      </DialogActions>
    </Dialog>
  );
};

const KeywordReviewDialog = ({ open, product, keywords, onClose, onAccept, onReject }) => {
  const [editableKeywords, setEditableKeywords] = useState([]);
  const [newKeyword, setNewKeyword] = useState('');

  useEffect(() => {
    if (keywords) setEditableKeywords([...keywords]);
  }, [keywords]);

  const handleRemoveKeyword = (index) => {
    setEditableKeywords(editableKeywords.filter((_, i) => i !== index));
  };

  const handleAddKeyword = () => {
    if (newKeyword.trim() && !editableKeywords.includes(newKeyword.trim().toLowerCase())) {
      setEditableKeywords([...editableKeywords, newKeyword.trim().toLowerCase()]);
      setNewKeyword('');
    }
  };

  const handleAccept = () => {
    onAccept(editableKeywords);
  };

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>
        Review Generated Keywords
        <Typography variant="body2" color="textSecondary">
          {product?.productName}
        </Typography>
      </DialogTitle>
      <DialogContent>
        <Box sx={{ mt: 2 }}>
          <Typography variant="subtitle2" gutterBottom>
            Generated Keywords (Review & Edit):
          </Typography>
          <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1, mb: 2 }}>
            {editableKeywords.map((keyword, index) => (
              <Chip
                key={index}
                label={keyword}
                onDelete={() => handleRemoveKeyword(index)}
                color="primary"
                variant="outlined"
              />
            ))}
          </Box>

          <Box sx={{ display: 'flex', gap: 1 }}>
            <TextField
              size="small"
              label="Add keyword"
              value={newKeyword}
              onChange={(e) => setNewKeyword(e.target.value)}
              onKeyPress={(e) => e.key === 'Enter' && handleAddKeyword()}
              fullWidth
            />
            <IconButton onClick={handleAddKeyword} color="primary">
              <Add />
            </IconButton>
          </Box>
        </Box>
      </DialogContent>
      <DialogActions>
        <Button onClick={onReject} startIcon={<Close />}>
          Reject
        </Button>
        <Button onClick={handleAccept} variant="contained" startIcon={<CheckCircle />}>
          Accept & Save
        </Button>
      </DialogActions>
    </Dialog>
  );
};

const StagingReview = () => {
  const [products, setProducts] = useState([]);
  const [loading, setLoading] = useState(true);
  const [selectedProducts, setSelectedProducts] = useState([]);
  const [editDialogOpen, setEditDialogOpen] = useState(false);
  const [editingProduct, setEditingProduct] = useState(null);
  const [message, setMessage] = useState(null);

  // Keyword generation state
  const [keywordDialogOpen, setKeywordDialogOpen] = useState(false);
  const [generatingKeywords, setGeneratingKeywords] = useState({});
  const [generatedKeywords, setGeneratedKeywords] = useState(null);
  const [keywordProduct, setKeywordProduct] = useState(null);

  useEffect(() => {
    loadProducts();
  }, []);

  const loadProducts = async () => {
    setLoading(true);
    try {
      const response = await stagingApi.getAllProducts(true); // pending only
      setProducts(response.data);
      setSelectedProducts([]);
    } catch (error) {
      setMessage({ type: 'error', text: 'Failed to load products' });
    } finally {
      setLoading(false);
    }
  };

  const handleApprove = async (productId) => {
    try {
      await stagingApi.approveProduct(productId, 'admin', 'Approved via UI');
      setMessage({ type: 'success', text: 'Product approved successfully' });
      loadProducts();
    } catch (error) {
      setMessage({ type: 'error', text: 'Failed to approve product' });
    }
  };

  const handleBulkApprove = async () => {
    if (selectedProducts.length === 0) return;

    try {
      await stagingApi.bulkApprove(selectedProducts, 'admin', 'Bulk approved via UI');
      setMessage({
        type: 'success',
        text: `${selectedProducts.length} products approved successfully`,
      });
      loadProducts();
    } catch (error) {
      setMessage({ type: 'error', text: 'Failed to bulk approve products' });
    }
  };

  const handleBulkReject = async () => {
    if (selectedProducts.length === 0) return;

    if (!window.confirm(`Are you sure you want to reject ${selectedProducts.length} product(s)?`)) return;

    try {
      await stagingApi.bulkReject(selectedProducts, 'admin', 'Bulk rejected via UI');
      setMessage({
        type: 'success',
        text: `${selectedProducts.length} products rejected successfully`,
      });
      loadProducts();
    } catch (error) {
      setMessage({ type: 'error', text: 'Failed to bulk reject products' });
    }
  };

  const handleBulkDelete = async () => {
    if (selectedProducts.length === 0) return;

    if (!window.confirm(`Are you sure you want to permanently delete ${selectedProducts.length} product(s)? This action cannot be undone.`)) return;

    try {
      await stagingApi.bulkDelete(selectedProducts);
      setMessage({
        type: 'success',
        text: `${selectedProducts.length} products deleted successfully`,
      });
      loadProducts();
    } catch (error) {
      setMessage({ type: 'error', text: 'Failed to bulk delete products' });
    }
  };

  const handleReject = async (productId) => {
    try {
      await stagingApi.rejectProduct(productId, 'admin', 'Rejected via UI');
      setMessage({ type: 'success', text: 'Product rejected' });
      loadProducts();
    } catch (error) {
      setMessage({ type: 'error', text: 'Failed to reject product' });
    }
  };

  const handleDelete = async (productId) => {
    if (!window.confirm('Are you sure you want to delete this product?')) return;

    try {
      await stagingApi.deleteProduct(productId);
      setMessage({ type: 'success', text: 'Product deleted' });
      loadProducts();
    } catch (error) {
      setMessage({ type: 'error', text: 'Failed to delete product' });
    }
  };

  const handleEdit = (product) => {
    setEditingProduct(product);
    setEditDialogOpen(true);
  };

  const handleGenerateKeywords = async (product) => {
    setGeneratingKeywords({ ...generatingKeywords, [product.id]: true });

    try {
      const response = await stagingApi.generateKeywords(product.id);
      setGeneratedKeywords(response.data.keywords);
      setKeywordProduct(product);
      setKeywordDialogOpen(true);
      setMessage({ type: 'success', text: `Generated ${response.data.keywords.length} keywords` });
    } catch (error) {
      setMessage({ type: 'error', text: 'Failed to generate keywords' });
    } finally {
      setGeneratingKeywords({ ...generatingKeywords, [product.id]: false });
    }
  };

  const handleAcceptKeywords = async (keywords) => {
    try {
      await stagingApi.saveKeywords(keywordProduct.id, keywords);
      setMessage({ type: 'success', text: 'Keywords saved successfully' });
      setKeywordDialogOpen(false);
      loadProducts();
    } catch (error) {
      setMessage({ type: 'error', text: 'Failed to save keywords' });
    }
  };

  const handleRejectKeywords = () => {
    setKeywordDialogOpen(false);
    setMessage({ type: 'info', text: 'Keywords discarded' });
  };

  const handleSelectAll = (event) => {
    if (event.target.checked) {
      setSelectedProducts(products.map((p) => p.id));
    } else {
      setSelectedProducts([]);
    }
  };

  const handleSelectProduct = (productId) => {
    setSelectedProducts((prev) =>
      prev.includes(productId)
        ? prev.filter((id) => id !== productId)
        : [...prev, productId]
    );
  };

  if (loading) {
    return (
      <Box display="flex" justifyContent="center" alignItems="center" minHeight="400px">
        <CircularProgress />
      </Box>
    );
  }

  return (
    <Box>
      <Box display="flex" justifyContent="space-between" alignItems="center" mb={3}>
        <Typography variant="h4">Review Staging Products</Typography>
        <Box display="flex" gap={2}>
          <Button
            variant="contained"
            color="success"
            startIcon={<CheckCircle />}
            onClick={handleBulkApprove}
            disabled={selectedProducts.length === 0}
          >
            Approve Selected ({selectedProducts.length})
          </Button>
          <Button
            variant="contained"
            color="warning"
            startIcon={<Cancel />}
            onClick={handleBulkReject}
            disabled={selectedProducts.length === 0}
          >
            Reject Selected ({selectedProducts.length})
          </Button>
          <Button
            variant="contained"
            color="error"
            startIcon={<Delete />}
            onClick={handleBulkDelete}
            disabled={selectedProducts.length === 0}
          >
            Delete Selected ({selectedProducts.length})
          </Button>
          <Button variant="outlined" startIcon={<Refresh />} onClick={loadProducts}>
            Refresh
          </Button>
        </Box>
      </Box>

      {message && (
        <Alert severity={message.type} onClose={() => setMessage(null)} sx={{ mb: 2 }}>
          {message.text}
        </Alert>
      )}

      {products.length === 0 ? (
        <Card>
          <CardContent>
            <Typography color="textSecondary" align="center">
              No pending products to review
            </Typography>
          </CardContent>
        </Card>
      ) : (
        <Card>
          <CardContent>
            <Table>
              <TableHead>
                <TableRow>
                  <TableCell padding="checkbox">
                    <Checkbox
                      checked={selectedProducts.length === products.length}
                      indeterminate={
                        selectedProducts.length > 0 &&
                        selectedProducts.length < products.length
                      }
                      onChange={handleSelectAll}
                    />
                  </TableCell>
                  <TableCell>Product Name</TableCell>
                  <TableCell>Category</TableCell>
                  <TableCell>Keywords</TableCell>
                  <TableCell>AI Suggestion</TableCell>
                  <TableCell>Actions</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {products.map((product) => (
                  <TableRow key={product.id}>
                    <TableCell padding="checkbox">
                      <Checkbox
                        checked={selectedProducts.includes(product.id)}
                        onChange={() => handleSelectProduct(product.id)}
                      />
                    </TableCell>
                    <TableCell>{product.productName}</TableCell>
                    <TableCell>
                      <Chip label={product.category || 'N/A'} size="small" />
                    </TableCell>
                    <TableCell>
                      {product.keywords && product.keywords.length > 0 ? (
                        <Box
                          sx={{
                            display: 'flex',
                            flexWrap: 'wrap',
                            gap: 0.5,
                            maxWidth: 200,
                            cursor: 'pointer',
                            '&:hover': { opacity: 0.8 }
                          }}
                          onClick={() => {
                            setGeneratedKeywords(product.keywords);
                            setKeywordProduct(product);
                            setKeywordDialogOpen(true);
                          }}
                          title="Click to view and edit keywords"
                        >
                          {product.keywords.slice(0, 3).map((keyword, idx) => (
                            <Chip key={idx} label={keyword} size="small" />
                          ))}
                          {product.keywords.length > 3 && (
                            <Chip label={`+${product.keywords.length - 3}`} size="small" variant="outlined" />
                          )}
                        </Box>
                      ) : (
                        <Button
                          size="small"
                          startIcon={generatingKeywords[product.id] ? <CircularProgress size={16} /> : <Psychology />}
                          onClick={() => handleGenerateKeywords(product)}
                          disabled={generatingKeywords[product.id]}
                        >
                          Generate
                        </Button>
                      )}
                    </TableCell>
                    <TableCell>
                      {product.aiSuggestedCategory ? (
                        <Chip
                          label={`${product.aiSuggestedCategory} (${
                            (product.aiConfidence * 100).toFixed(0)
                          }%)`}
                          size="small"
                          color="primary"
                          variant="outlined"
                        />
                      ) : (
                        'N/A'
                      )}
                    </TableCell>
                    <TableCell>
                      <Box display="flex" gap={1}>
                        <IconButton
                          size="small"
                          color="success"
                          onClick={() => handleApprove(product.id)}
                          title="Approve"
                        >
                          <CheckCircle />
                        </IconButton>
                        <IconButton
                          size="small"
                          color="primary"
                          onClick={() => handleEdit(product)}
                          title="Edit"
                        >
                          <Edit />
                        </IconButton>
                        <IconButton
                          size="small"
                          color="warning"
                          onClick={() => handleReject(product.id)}
                          title="Reject"
                        >
                          <Cancel />
                        </IconButton>
                        <IconButton
                          size="small"
                          color="error"
                          onClick={() => handleDelete(product.id)}
                          title="Delete"
                        >
                          <Delete />
                        </IconButton>
                      </Box>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}

      <ProductEditDialog
        open={editDialogOpen}
        product={editingProduct}
        onClose={() => setEditDialogOpen(false)}
        onSave={() => {
          setEditDialogOpen(false);
          loadProducts();
        }}
      />

      <KeywordReviewDialog
        open={keywordDialogOpen}
        product={keywordProduct}
        keywords={generatedKeywords}
        onClose={() => setKeywordDialogOpen(false)}
        onAccept={handleAcceptKeywords}
        onReject={handleRejectKeywords}
      />
    </Box>
  );
};

export default StagingReview;
