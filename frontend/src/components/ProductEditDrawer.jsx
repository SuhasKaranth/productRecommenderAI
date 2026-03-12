import React, { useState, useEffect } from 'react';
import {
  Box,
  Typography,
  TextField,
  Button,
  IconButton,
  Chip,
  Alert,
  CircularProgress,
  Dialog,
  DialogTitle,
  DialogContent,
  DialogActions,
  Autocomplete,
  Link,
  Tooltip,
} from '@mui/material';
import CloseIcon from '@mui/icons-material/Close';
import LockIcon from '@mui/icons-material/Lock';
import LaunchIcon from '@mui/icons-material/Launch';
import WarningAmberIcon from '@mui/icons-material/WarningAmber';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import { productApi } from '../services/api';

// State machine states
const STATE = {
  CLOSED: 'CLOSED',
  LOADING_PRODUCT: 'LOADING_PRODUCT',
  OPEN_IDLE: 'OPEN_IDLE',
  GENERATING_SUMMARY: 'GENERATING_SUMMARY',
  GENERATING_KEYWORDS: 'GENERATING_KEYWORDS',
  SAVING: 'SAVING',
  REFRESHING: 'REFRESHING',
  OPEN_ERROR: 'OPEN_ERROR',
  CONFIRM_DISCARD: 'CONFIRM_DISCARD',
};

// Static keyword suggestions per product category
const CATEGORY_SUGGESTIONS = {
  'CREDIT_CARD': ['cashback', 'air-miles', 'rewards', 'sharia-compliant', 'no-annual-fee'],
  'FINANCING': ['murabaha', 'tawarruq', 'personal-finance', 'flexible-tenure', 'low-profit-rate'],
  'SAVINGS_ACCOUNT': ['savings', 'profit-sharing', 'mudharabah', 'monthly-profit', 'no-minimum-balance'],
  'INVESTMENT': ['investment', 'wakala', 'capital-protection', 'wealth-management', 'portfolio'],
  'TAKAFUL': ['takaful', 'family-protection', 'medical-cover', 'sharia-compliant', 'group-takaful'],
};

/**
 * Returns a chip label and color based on how many days ago the product was last scraped.
 * - Never scraped: grey "Never scraped"
 * - Scraped today: green "Scraped today"
 * - Scraped 1-30 days ago: green "Scraped Xd ago"
 * - Scraped 31+ days ago: yellow "Stale — Xd ago"
 */
const getScrapedAtChip = (scrapedAt) => {
  if (!scrapedAt) return { label: 'Never scraped', color: 'default' };
  const daysAgo = Math.floor((Date.now() - new Date(scrapedAt)) / (1000 * 60 * 60 * 24));
  if (daysAgo > 30) return { label: `Stale — ${daysAgo}d ago`, color: 'warning' };
  if (daysAgo === 0) return { label: 'Scraped today', color: 'success' };
  return { label: `Scraped ${daysAgo}d ago`, color: 'success' };
};

const SHARIA_TERMS = /\b(interest|loan|mortgage|insurance|conventional)\b/i;

const ProductEditDrawer = ({ productId, onClose, onSaveSuccess }) => {
  const [modalState, setModalState] = useState(STATE.CLOSED);
  const [product, setProduct] = useState(null);
  const [summary, setSummary] = useState('');
  const [keywords, setKeywords] = useState([]);
  const [isDirty, setIsDirty] = useState(false);
  const [errorMessage, setErrorMessage] = useState('');
  const [scrapedAt, setScrapedAt] = useState(null);
  const [refreshSuccess, setRefreshSuccess] = useState(false);

  // Load product when productId changes
  useEffect(() => {
    if (productId == null) {
      setModalState(STATE.CLOSED);
      return;
    }
    setModalState(STATE.LOADING_PRODUCT);
    setIsDirty(false);
    setErrorMessage('');
    setRefreshSuccess(false);

    productApi.getProduct(productId)
      .then((res) => {
        const p = res.data;
        setProduct(p);
        setSummary(p.description || '');
        setKeywords(p.keywords || []);
        setScrapedAt(p.scrapedAt || null);
        setModalState(STATE.OPEN_IDLE);
      })
      .catch((err) => {
        setErrorMessage(err.response?.data?.message || err.message || 'Failed to load product');
        setModalState(STATE.OPEN_ERROR);
      });
  }, [productId]);

  const isOpen = modalState !== STATE.CLOSED;
  const isBusy = [STATE.LOADING_PRODUCT, STATE.GENERATING_SUMMARY, STATE.GENERATING_KEYWORDS, STATE.SAVING, STATE.REFRESHING].includes(modalState);
  const shariaWarning = summary && SHARIA_TERMS.test(summary);

  // Word count — derived inline, no new state
  const wordCount = summary.trim() ? summary.trim().split(/\s+/).length : 0;

  // Keyword suggestions — filtered against already-added keywords
  const suggestions = (CATEGORY_SUGGESTIONS[product?.category] ?? []).filter(
    (tag) => !keywords.includes(tag)
  );

  const handleGenerateSummary = async () => {
    setModalState(STATE.GENERATING_SUMMARY);
    try {
      const res = await productApi.generateSummary(productId);
      setSummary(res.data.summary || '');
      setIsDirty(true);
      setModalState(STATE.OPEN_IDLE);
    } catch (err) {
      setErrorMessage(err.response?.data?.error || err.message || 'Failed to generate summary');
      setModalState(STATE.OPEN_ERROR);
    }
  };

  const handleGenerateKeywords = async () => {
    setModalState(STATE.GENERATING_KEYWORDS);
    try {
      const res = await productApi.generateKeywords(productId);
      setKeywords(res.data.keywords || []);
      setIsDirty(true);
      setModalState(STATE.OPEN_IDLE);
    } catch (err) {
      setErrorMessage(err.response?.data?.error || err.message || 'Failed to generate keywords');
      setModalState(STATE.OPEN_ERROR);
    }
  };

  const handleSave = async () => {
    setModalState(STATE.SAVING);
    try {
      await productApi.saveKeywords(productId, keywords);
      await productApi.updateProduct(productId, { description: summary });
      setIsDirty(false);
      onSaveSuccess();
    } catch (err) {
      setErrorMessage(err.response?.data?.message || err.message || 'Failed to save');
      setModalState(STATE.OPEN_ERROR);
    }
  };

  const handleCloseRequest = () => {
    if (isDirty) {
      setModalState(STATE.CONFIRM_DISCARD);
    } else {
      onClose();
    }
  };

  const handleConfirmDiscard = () => {
    setIsDirty(false);
    onClose();
  };

  const handleCancelDiscard = () => {
    setModalState(STATE.OPEN_IDLE);
  };

  const handleSummaryChange = (value) => {
    setSummary(value);
    setIsDirty(true);
    if (modalState === STATE.OPEN_ERROR) setModalState(STATE.OPEN_IDLE);
  };

  const handleRefreshContent = async () => {
    setModalState(STATE.REFRESHING);
    setRefreshSuccess(false);
    try {
      const res = await productApi.refreshContent(productId);
      setScrapedAt(res.data.scrapedAt);
      setRefreshSuccess(true);
      setModalState(STATE.OPEN_IDLE);
    } catch (err) {
      setErrorMessage(err.response?.data?.error || err.message || 'Failed to refresh page content');
      setModalState(STATE.OPEN_ERROR);
    }
  };

  const handleAddSuggestion = (tag) => {
    setKeywords([...keywords, tag]);
    setIsDirty(true);
  };

  // Enrichment status helpers
  const hasRawContent = Boolean(scrapedAt);
  const hasSummary = summary.trim().length > 0;
  const hasKeywords = keywords.length > 0;

  const EnrichmentRow = ({ ok, label }) => (
    <Box display="flex" alignItems="center" gap={0.75}>
      {ok
        ? <CheckCircleIcon fontSize="small" color="success" />
        : <WarningAmberIcon fontSize="small" color="warning" />
      }
      <Typography variant="body2" color="text.secondary">{label}</Typography>
    </Box>
  );

  return (
    <>
      <Dialog
        open={isOpen}
        onClose={handleCloseRequest}
        maxWidth="lg"
        fullWidth
      >
        {/* Dialog title / header */}
        <DialogTitle sx={{ pb: 1 }}>
          <Box display="flex" alignItems="center" justifyContent="space-between">
            <Typography variant="h6">
              {product ? product.productName : (modalState === STATE.LOADING_PRODUCT ? 'Loading…' : 'Edit Product')}
            </Typography>
            <IconButton onClick={handleCloseRequest} size="small" sx={{ ml: 1 }}>
              <CloseIcon />
            </IconButton>
          </Box>
        </DialogTitle>

        {/* Dialog body — two-column grid */}
        <DialogContent dividers>
          {modalState === STATE.LOADING_PRODUCT && (
            <Box display="flex" justifyContent="center" pt={6}>
              <CircularProgress />
            </Box>
          )}

          {modalState === STATE.OPEN_ERROR && (
            <Alert
              severity="error"
              onClose={() => setModalState(STATE.OPEN_IDLE)}
              sx={{ mb: 2 }}
            >
              {errorMessage}
            </Alert>
          )}

          {[STATE.OPEN_IDLE, STATE.GENERATING_SUMMARY, STATE.GENERATING_KEYWORDS, STATE.SAVING, STATE.REFRESHING, STATE.CONFIRM_DISCARD].includes(modalState) && (
            <Box
              sx={{
                display: 'grid',
                gridTemplateColumns: { xs: '1fr', md: '1fr 1fr' },
                gap: 3,
              }}
            >
              {/* LEFT COLUMN — read-only product metadata */}
              <Box sx={{ overflowY: 'auto' }}>
                {product && (
                  <>
                    {/* Product code, category, subCategory */}
                    <Box mb={2}>
                      {product.productCode && (
                        <Box mb={0.5}>
                          <Typography variant="caption" color="text.secondary">Product Code</Typography>
                          <Typography variant="body2">{product.productCode}</Typography>
                        </Box>
                      )}
                      {product.category && (
                        <Box mb={0.5}>
                          <Typography variant="caption" color="text.secondary">Category</Typography>
                          <Typography variant="body2">{product.category}</Typography>
                        </Box>
                      )}
                      {product.subCategory && (
                        <Box mb={0.5}>
                          <Typography variant="caption" color="text.secondary">Sub-category</Typography>
                          <Typography variant="body2">{product.subCategory}</Typography>
                        </Box>
                      )}
                    </Box>

                    {/* Chips: islamic structure, active status */}
                    <Box display="flex" gap={0.75} flexWrap="wrap" mb={2}>
                      {product.islamicStructure && (
                        <Chip label={product.islamicStructure} size="small" variant="outlined" />
                      )}
                      {product.active != null && (
                        <Chip
                          label={product.active ? 'Active' : 'Inactive'}
                          size="small"
                          color={product.active ? 'success' : 'default'}
                        />
                      )}
                    </Box>

                    {/* Financial details */}
                    <Box mb={2}>
                      <Box mb={0.5}>
                        <Typography variant="caption" color="text.secondary">Annual Fee</Typography>
                        <Typography variant="body2">
                          {product.annualFee != null ? `AED ${product.annualFee}` : '—'}
                        </Typography>
                      </Box>
                      <Box mb={0.5}>
                        <Typography variant="caption" color="text.secondary">Minimum Income</Typography>
                        <Typography variant="body2">
                          {product.minIncome != null ? `AED ${product.minIncome}` : '—'}
                        </Typography>
                      </Box>
                    </Box>

                    {/* Scraped-at staleness chip + Refresh Data button */}
                    <Box display="flex" gap={1} mb={2} alignItems="center" flexWrap="wrap">
                      {(() => {
                        const chipInfo = getScrapedAtChip(scrapedAt);
                        return (
                          <Chip label={chipInfo.label} size="small" color={chipInfo.color} />
                        );
                      })()}
                      <Button
                        size="small"
                        variant="outlined"
                        onClick={handleRefreshContent}
                        disabled={isBusy || !product?.sourceUrl}
                        title={!product?.sourceUrl ? 'No source URL configured for this product' : ''}
                        startIcon={modalState === STATE.REFRESHING ? <CircularProgress size={12} /> : null}
                      >
                        {modalState === STATE.REFRESHING ? 'Refreshing...' : 'Refresh Data'}
                      </Button>
                    </Box>

                    {/* Source URL */}
                    <Box mb={2}>
                      <Typography variant="caption" color="text.secondary">Source URL</Typography>
                      <Box>
                        {product.sourceUrl ? (
                          <Link
                            href={product.sourceUrl}
                            target="_blank"
                            rel="noopener"
                            variant="body2"
                            sx={{ display: 'inline-flex', alignItems: 'center', gap: 0.25 }}
                          >
                            {product.sourceUrl}
                            <LaunchIcon fontSize="small" />
                          </Link>
                        ) : (
                          <Typography variant="body2" color="text.disabled">No source URL</Typography>
                        )}
                      </Box>
                    </Box>

                    {/* Enrichment status checklist */}
                    <Box>
                      <Typography variant="caption" color="text.secondary" sx={{ mb: 0.5, display: 'block' }}>
                        Enrichment Status
                      </Typography>
                      <Box display="flex" flexDirection="column" gap={0.5}>
                        <EnrichmentRow ok={hasRawContent} label="Raw content" />
                        <EnrichmentRow ok={hasSummary} label="Summary" />
                        <EnrichmentRow ok={hasKeywords} label="Keywords" />
                      </Box>
                    </Box>

                    {/* Refresh success banner (inside left column) */}
                    {refreshSuccess && (
                      <Alert severity="info" onClose={() => setRefreshSuccess(false)} sx={{ mt: 2 }}>
                        Page content refreshed — click "Generate Summary (AI)" to regenerate with latest data.
                      </Alert>
                    )}
                  </>
                )}
              </Box>

              {/* RIGHT COLUMN — summary + keywords editor */}
              <Box sx={{ overflowY: 'auto' }}>
                {/* Summary section */}
                <Box mb={3}>
                  <Box display="flex" justifyContent="space-between" alignItems="center" mb={1}>
                    <Typography variant="subtitle2">Summary</Typography>
                    <Box display="flex" alignItems="center" gap={0.75}>
                      <Button
                        size="small"
                        variant="outlined"
                        onClick={handleGenerateSummary}
                        disabled={isBusy}
                        startIcon={modalState === STATE.GENERATING_SUMMARY ? <CircularProgress size={14} /> : null}
                      >
                        {modalState === STATE.GENERATING_SUMMARY ? 'Generating…' : 'Generate Summary (AI)'}
                      </Button>
                      {/* Sharia inline indicator */}
                      {shariaWarning && (
                        <Tooltip title="Non-compliant term detected">
                          <WarningAmberIcon color="error" fontSize="small" />
                        </Tooltip>
                      )}
                      {!shariaWarning && summary && (
                        <LockIcon color="success" fontSize="small" />
                      )}
                    </Box>
                  </Box>
                  <TextField
                    fullWidth
                    multiline
                    rows={5}
                    value={summary}
                    onChange={(e) => handleSummaryChange(e.target.value)}
                    placeholder="Product summary…"
                    disabled={isBusy}
                  />
                  {/* Word count indicator */}
                  <Typography
                    variant="caption"
                    sx={{
                      color: wordCount > 150
                        ? 'error.main'
                        : wordCount > 140
                          ? 'warning.main'
                          : 'text.secondary',
                    }}
                  >
                    {wordCount} word{wordCount !== 1 ? 's' : ''}
                  </Typography>
                </Box>

                {/* Keywords section */}
                <Box>
                  <Box display="flex" justifyContent="space-between" alignItems="center" mb={1}>
                    <Typography variant="subtitle2">Keywords</Typography>
                    <Button
                      size="small"
                      variant="outlined"
                      onClick={handleGenerateKeywords}
                      disabled={isBusy}
                      startIcon={modalState === STATE.GENERATING_KEYWORDS ? <CircularProgress size={14} /> : null}
                    >
                      {modalState === STATE.GENERATING_KEYWORDS ? 'Generating…' : 'Generate Keywords (AI)'}
                    </Button>
                  </Box>
                  <Autocomplete
                    multiple
                    freeSolo
                    options={[]}
                    value={keywords}
                    onChange={(_, newValue) => {
                      setKeywords(newValue);
                      setIsDirty(true);
                    }}
                    disabled={isBusy}
                    renderTags={(value, getTagProps) =>
                      value.map((option, index) => (
                        <Chip
                          key={index}
                          label={option}
                          size="small"
                          variant="outlined"
                          {...getTagProps({ index })}
                        />
                      ))
                    }
                    renderInput={(params) => (
                      <TextField
                        {...params}
                        placeholder={keywords.length === 0 ? 'No keywords — type and press Enter to add' : ''}
                      />
                    )}
                  />

                  {/* Keyword quick-add suggestion chips */}
                  {suggestions.length > 0 && !isBusy && (
                    <Box display="flex" flexWrap="wrap" gap={0.75} mt={1}>
                      <Typography variant="caption" color="text.secondary" sx={{ width: '100%' }}>
                        Suggested:
                      </Typography>
                      {suggestions.map((tag) => (
                        <Chip
                          key={tag}
                          label={tag}
                          size="small"
                          variant="outlined"
                          onClick={() => handleAddSuggestion(tag)}
                          sx={{ cursor: 'pointer' }}
                        />
                      ))}
                    </Box>
                  )}
                </Box>
              </Box>
            </Box>
          )}
        </DialogContent>

        {/* Footer actions */}
        {[STATE.OPEN_IDLE, STATE.GENERATING_SUMMARY, STATE.GENERATING_KEYWORDS, STATE.SAVING, STATE.REFRESHING, STATE.OPEN_ERROR, STATE.CONFIRM_DISCARD].includes(modalState) && (
          <DialogActions sx={{ px: 3, py: 2 }}>
            <Button onClick={handleCloseRequest} disabled={modalState === STATE.SAVING}>
              Cancel
            </Button>
            <Button
              variant="contained"
              onClick={handleSave}
              disabled={isBusy || !isDirty}
              startIcon={modalState === STATE.SAVING ? <CircularProgress size={16} color="inherit" /> : null}
            >
              {modalState === STATE.SAVING ? 'Saving…' : 'Save'}
            </Button>
          </DialogActions>
        )}
      </Dialog>

      {/* Discard confirmation dialog — unchanged */}
      <Dialog open={modalState === STATE.CONFIRM_DISCARD} onClose={handleCancelDiscard}>
        <DialogTitle>Discard unsaved changes?</DialogTitle>
        <DialogActions>
          <Button onClick={handleCancelDiscard}>Keep Editing</Button>
          <Button onClick={handleConfirmDiscard} color="error">Discard</Button>
        </DialogActions>
      </Dialog>
    </>
  );
};

export default ProductEditDrawer;
