import React, { useState } from 'react';
import {
  Box,
  Card,
  CardContent,
  Typography,
  TextField,
  Button,
  CircularProgress,
  Alert,
  Grid,
  Chip,
  Divider,
  Avatar,
  Paper,
  Collapse,
  IconButton,
} from '@mui/material';
import {
  AutoAwesome,
  Send,
  Person,
  ExpandMore,
  ExpandLess,
  Psychology,
  CheckCircle,
} from '@mui/icons-material';
import { recommendationApi } from '../services/api';

const TestRecommendations = () => {
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);
  const [showAnalysis, setShowAnalysis] = useState(false);
  const [submittedQuery, setSubmittedQuery] = useState('');

  const handleSubmit = async () => {
    if (!query.trim()) {
      setError('Please enter a query');
      return;
    }

    setLoading(true);
    setError(null);
    setResult(null);
    setSubmittedQuery(query.trim());

    try {
      const response = await recommendationApi.getRecommendations(query);
      setResult(response.data);
    } catch (err) {
      setError(
        err.response?.data?.message ||
          err.message ||
          'Failed to fetch recommendations. Please check if the API is running on port 8080.'
      );
    } finally {
      setLoading(false);
    }
  };

  const handleClear = () => {
    setQuery('');
    setResult(null);
    setError(null);
    setSubmittedQuery('');
    setShowAnalysis(false);
  };

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
      handleSubmit();
    }
  };

  const getScoreColor = (score) => {
    if (score >= 0.8) return 'success';
    if (score >= 0.6) return 'warning';
    return 'error';
  };

  const formatPct = (value) => `${(value * 100).toFixed(0)}%`;

  const getCategoryColor = (category) => {
    const map = {
      COVERED_CARDS: '#1565c0',
      DEBIT_CARDS: '#2e7d32',
      CHARGE_CARDS: '#6a1b9a',
      HOME_FINANCE: '#e65100',
      PERSONAL_FINANCE: '#00695c',
      AUTO_FINANCE: '#4527a0',
      TAKAFUL: '#ad1457',
      SAVINGS: '#f57f17',
      CURRENT_ACCOUNTS: '#37474f',
      INVESTMENTS: '#1b5e20',
    };
    return map[category] || '#1976d2';
  };

  return (
    <Box>
      {/* Header */}
      <Box display="flex" justifyContent="space-between" alignItems="center" mb={3}>
        <Typography variant="h4">Test Recommendations</Typography>
        {result && (
          <Button variant="outlined" onClick={handleClear}>
            New Query
          </Button>
        )}
      </Box>

      {/* Input Card */}
      <Card sx={{ mb: 3 }}>
        <CardContent>
          <Typography variant="h6" gutterBottom>
            Ask SmartGuide
          </Typography>
          <Typography variant="body2" color="textSecondary" gutterBottom>
            Describe what you're looking for — the AI will recommend the best matching products.
            Press Ctrl+Enter to submit.
          </Typography>

          <Box sx={{ mt: 2 }}>
            <TextField
              fullWidth
              multiline
              rows={3}
              label="Your query"
              placeholder="e.g., I need a debit card for everyday spending, Looking for home finance options, Best travel card with lounge access..."
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={handleKeyDown}
              disabled={loading}
              sx={{ mb: 2 }}
            />

            <Button
              variant="contained"
              size="large"
              startIcon={loading ? <CircularProgress size={20} color="inherit" /> : <Send />}
              onClick={handleSubmit}
              disabled={loading || !query.trim()}
            >
              {loading ? 'Thinking...' : 'Get Recommendations'}
            </Button>
          </Box>

          {error && (
            <Alert severity="error" onClose={() => setError(null)} sx={{ mt: 2 }}>
              {error}
            </Alert>
          )}
        </CardContent>
      </Card>

      {/* Chat-style Results */}
      {result && (
        <Box>
          {/* User bubble */}
          <Box display="flex" justifyContent="flex-end" mb={2}>
            <Box display="flex" alignItems="flex-start" gap={1} maxWidth="75%">
              <Paper
                elevation={1}
                sx={{
                  px: 2.5,
                  py: 1.5,
                  bgcolor: 'primary.main',
                  color: 'primary.contrastText',
                  borderRadius: '18px 18px 4px 18px',
                }}
              >
                <Typography variant="body1">{submittedQuery}</Typography>
              </Paper>
              <Avatar sx={{ bgcolor: 'primary.dark', width: 36, height: 36 }}>
                <Person fontSize="small" />
              </Avatar>
            </Box>
          </Box>

          {/* AI response bubble */}
          <Box display="flex" justifyContent="flex-start" mb={3}>
            <Box display="flex" alignItems="flex-start" gap={1} width="100%">
              <Avatar sx={{ bgcolor: 'secondary.main', width: 36, height: 36, flexShrink: 0 }}>
                <AutoAwesome fontSize="small" />
              </Avatar>

              <Box flex={1}>
                <Paper
                  elevation={2}
                  sx={{
                    p: 3,
                    borderRadius: '4px 18px 18px 18px',
                    bgcolor: 'background.paper',
                  }}
                >
                  {/* Summary text */}
                  {result.summary ? (
                    <Typography
                      variant="body1"
                      sx={{ mb: 3, lineHeight: 1.7, color: 'text.primary', fontSize: '1rem' }}
                    >
                      {result.summary}
                    </Typography>
                  ) : result.message ? (
                    <Typography variant="body1" sx={{ mb: 3, lineHeight: 1.7 }}>
                      {result.message}
                    </Typography>
                  ) : result.recommendations?.length > 0 ? (
                    <Typography variant="body1" sx={{ mb: 3, lineHeight: 1.7 }}>
                      Here are the best matching products based on your query.
                    </Typography>
                  ) : null}

                  {/* Product cards */}
                  {result.recommendations && result.recommendations.length > 0 ? (
                    <>
                      <Typography
                        variant="overline"
                        color="textSecondary"
                        display="block"
                        sx={{ mb: 1.5, letterSpacing: 1.2 }}
                      >
                        Recommended Products · {result.recommendations.length} found
                      </Typography>

                      <Grid container spacing={2}>
                        {result.recommendations.map((rec) => (
                          <Grid item xs={12} sm={6} key={rec.rank}>
                            <Card
                              variant="outlined"
                              sx={{
                                height: '100%',
                                display: 'flex',
                                flexDirection: 'column',
                                borderLeft: `4px solid ${getCategoryColor(rec.category)}`,
                                '&:hover': { boxShadow: 3 },
                                transition: 'box-shadow 0.2s',
                              }}
                            >
                              <CardContent sx={{ flex: 1 }}>
                                {/* Rank + Score */}
                                <Box display="flex" justifyContent="space-between" alignItems="center" mb={1}>
                                  <Chip
                                    label={`#${rec.rank}`}
                                    size="small"
                                    color={rec.rank === 1 ? 'primary' : 'default'}
                                    sx={{ fontWeight: 'bold' }}
                                  />
                                  <Chip
                                    label={`${formatPct(rec.relevanceScore)} match`}
                                    size="small"
                                    color={getScoreColor(rec.relevanceScore)}
                                  />
                                </Box>

                                {/* Product name */}
                                <Typography variant="subtitle1" fontWeight="bold" gutterBottom>
                                  {rec.productName}
                                </Typography>

                                {/* Islamic structure + category */}
                                <Box display="flex" gap={1} flexWrap="wrap" mb={1.5}>
                                  <Chip
                                    label={rec.category?.replace(/_/g, ' ')}
                                    size="small"
                                    sx={{
                                      bgcolor: getCategoryColor(rec.category),
                                      color: '#fff',
                                      fontSize: '0.7rem',
                                    }}
                                  />
                                  {rec.islamicStructure && (
                                    <Chip
                                      label={rec.islamicStructure}
                                      size="small"
                                      variant="outlined"
                                      sx={{ fontSize: '0.7rem' }}
                                    />
                                  )}
                                </Box>

                                {/* Recommendation reason */}
                                <Typography
                                  variant="body2"
                                  color="text.secondary"
                                  sx={{ mb: 1.5, fontStyle: 'italic', lineHeight: 1.5 }}
                                >
                                  {rec.reason}
                                </Typography>

                                {/* Key benefits */}
                                {rec.keyBenefits && rec.keyBenefits.length > 0 && (
                                  <Box mb={1.5}>
                                    <Typography variant="caption" color="textSecondary" fontWeight="bold">
                                      KEY BENEFITS
                                    </Typography>
                                    <Box mt={0.5}>
                                      {rec.keyBenefits.slice(0, 4).map((benefit, idx) => (
                                        <Typography
                                          key={idx}
                                          variant="caption"
                                          display="block"
                                          color="text.primary"
                                          sx={{ lineHeight: 1.6 }}
                                        >
                                          · {benefit}
                                        </Typography>
                                      ))}
                                      {rec.keyBenefits.length > 4 && (
                                        <Typography variant="caption" color="textSecondary">
                                          +{rec.keyBenefits.length - 4} more
                                        </Typography>
                                      )}
                                    </Box>
                                  </Box>
                                )}

                                <Divider sx={{ my: 1 }} />

                                {/* Annual fee */}
                                <Box display="flex" justifyContent="space-between" alignItems="center">
                                  <Typography variant="caption" color="textSecondary">
                                    Annual Fee
                                  </Typography>
                                  <Typography variant="caption" fontWeight="bold">
                                    {rec.annualFee != null
                                      ? rec.annualFee === 0
                                        ? 'Free'
                                        : `AED ${Number(rec.annualFee).toLocaleString()}`
                                      : 'N/A'}
                                  </Typography>
                                </Box>

                                {rec.minIncome != null && (
                                  <Box display="flex" justifyContent="space-between" alignItems="center" mt={0.5}>
                                    <Typography variant="caption" color="textSecondary">
                                      Min. Income
                                    </Typography>
                                    <Typography variant="caption" fontWeight="bold">
                                      AED {Number(rec.minIncome).toLocaleString()}
                                    </Typography>
                                  </Box>
                                )}
                              </CardContent>
                            </Card>
                          </Grid>
                        ))}
                      </Grid>
                    </>
                  ) : (
                    <Alert severity="info">
                      No products matched your query. Try rephrasing or using different keywords.
                    </Alert>
                  )}

                  {/* Analysis toggle */}
                  <Box
                    display="flex"
                    alignItems="center"
                    mt={3}
                    sx={{ cursor: 'pointer', userSelect: 'none' }}
                    onClick={() => setShowAnalysis((v) => !v)}
                  >
                    <Typography variant="caption" color="textSecondary" sx={{ flex: 1 }}>
                      Analysis Details
                    </Typography>
                    <IconButton size="small">
                      {showAnalysis ? <ExpandLess fontSize="small" /> : <ExpandMore fontSize="small" />}
                    </IconButton>
                  </Box>

                  <Collapse in={showAnalysis}>
                    <Divider sx={{ mb: 2 }} />
                    <Grid container spacing={2}>
                      {/* Intent */}
                      <Grid item xs={12} sm={6}>
                        <Box display="flex" alignItems="center" gap={1} mb={1}>
                          <Psychology fontSize="small" color="primary" />
                          <Typography variant="caption" fontWeight="bold" color="textSecondary">
                            DETECTED INTENT
                          </Typography>
                        </Box>
                        <Chip
                          label={result.intent?.detectedIntent}
                          color="primary"
                          size="small"
                          sx={{ fontWeight: 'bold', mb: 1 }}
                        />
                        <Box display="flex" alignItems="center" gap={1}>
                          <Typography variant="caption" color="textSecondary">Confidence:</Typography>
                          <Chip
                            label={formatPct(result.intent?.confidence ?? 0)}
                            size="small"
                            color={getScoreColor(result.intent?.confidence ?? 0)}
                          />
                        </Box>

                        {result.intent?.entities && Object.keys(result.intent.entities).length > 0 && (
                          <Box mt={1} display="flex" flexWrap="wrap" gap={0.5}>
                            {Object.entries(result.intent.entities).map(([k, v]) => (
                              <Chip key={k} label={`${k}: ${v}`} size="small" variant="outlined" />
                            ))}
                          </Box>
                        )}
                      </Grid>

                      {/* Metrics */}
                      <Grid item xs={12} sm={6}>
                        <Box display="flex" alignItems="center" gap={1} mb={1}>
                          <CheckCircle fontSize="small" color="success" />
                          <Typography variant="caption" fontWeight="bold" color="textSecondary">
                            PROCESSING METRICS
                          </Typography>
                        </Box>
                        <Box display="flex" gap={1} flexWrap="wrap">
                          <Chip
                            label={result.status?.toUpperCase()}
                            color="success"
                            size="small"
                          />
                          <Chip
                            label={`${result.processingTimeMs} ms`}
                            size="small"
                            variant="outlined"
                          />
                        </Box>
                        <Typography variant="caption" color="textSecondary" display="block" mt={1}>
                          {new Date().toLocaleString()}
                        </Typography>
                      </Grid>
                    </Grid>
                  </Collapse>
                </Paper>
              </Box>
            </Box>
          </Box>
        </Box>
      )}
    </Box>
  );
};

export default TestRecommendations;
