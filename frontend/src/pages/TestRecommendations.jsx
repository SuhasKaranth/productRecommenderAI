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
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableRow,
  Divider,
} from '@mui/material';
import { Psychology, Send, CheckCircle } from '@mui/icons-material';
import { recommendationApi } from '../services/api';

const TestRecommendations = () => {
  const [query, setQuery] = useState('');
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState(null);
  const [error, setError] = useState(null);

  const handleSubmit = async () => {
    if (!query.trim()) {
      setError('Please enter a query');
      return;
    }

    setLoading(true);
    setError(null);
    setResult(null);

    try {
      const response = await recommendationApi.getRecommendations(query);
      setResult(response.data);
    } catch (err) {
      setError(
        err.response?.data?.message ||
          err.message ||
          'Failed to fetch recommendations. Please check if the API is running on port 8000.'
      );
    } finally {
      setLoading(false);
    }
  };

  const handleClear = () => {
    setQuery('');
    setResult(null);
    setError(null);
  };

  const getConfidenceColor = (confidence) => {
    if (confidence >= 0.8) return 'success';
    if (confidence >= 0.6) return 'warning';
    return 'error';
  };

  const formatConfidence = (confidence) => {
    return `${(confidence * 100).toFixed(0)}%`;
  };

  return (
    <Box>
      <Box display="flex" justifyContent="space-between" alignItems="center" mb={3}>
        <Typography variant="h4">Test Recommendations</Typography>
        {result && (
          <Button variant="outlined" onClick={handleClear}>
            Clear Results
          </Button>
        )}
      </Box>

      {/* Input Card */}
      <Card sx={{ mb: 3 }}>
        <CardContent>
          <Typography variant="h6" gutterBottom>
            Enter Your Query
          </Typography>
          <Typography variant="body2" color="textSecondary" gutterBottom>
            Test the product recommendation engine by entering a natural language query
          </Typography>

          <Box sx={{ mt: 2 }}>
            <TextField
              fullWidth
              multiline
              rows={3}
              label="Query"
              placeholder="e.g., I need a car loan, Looking for a travel credit card, Home financing options..."
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              disabled={loading}
              sx={{ mb: 2 }}
            />

            <Button
              variant="contained"
              size="large"
              startIcon={loading ? <CircularProgress size={20} /> : <Send />}
              onClick={handleSubmit}
              disabled={loading || !query.trim()}
            >
              {loading ? 'Processing...' : 'Get Recommendations'}
            </Button>
          </Box>

          {error && (
            <Alert severity="error" onClose={() => setError(null)} sx={{ mt: 2 }}>
              {error}
            </Alert>
          )}
        </CardContent>
      </Card>

      {/* Results Section */}
      {result && (
        <>
          {/* Intent and Metrics Row */}
          <Grid container spacing={3} sx={{ mb: 3 }}>
            {/* Intent Card */}
            <Grid item xs={12} md={6}>
              <Card>
                <CardContent>
                  <Box display="flex" alignItems="center" mb={2}>
                    <Psychology sx={{ mr: 1, color: 'primary.main' }} />
                    <Typography variant="h6">Detected Intent</Typography>
                  </Box>

                  <Box sx={{ mb: 2 }}>
                    <Chip
                      label={result.intent.detectedIntent}
                      size="large"
                      color="primary"
                      sx={{ fontSize: '1.1rem', fontWeight: 'bold' }}
                    />
                  </Box>

                  <Divider sx={{ my: 2 }} />

                  <Box>
                    <Typography variant="body2" color="textSecondary" gutterBottom>
                      Confidence Level
                    </Typography>
                    <Chip
                      label={formatConfidence(result.intent.confidence)}
                      color={getConfidenceColor(result.intent.confidence)}
                      size="medium"
                    />
                  </Box>

                  {result.intent.entities && Object.keys(result.intent.entities).length > 0 && (
                    <>
                      <Divider sx={{ my: 2 }} />
                      <Box>
                        <Typography variant="body2" color="textSecondary" gutterBottom>
                          Extracted Entities
                        </Typography>
                        <Box sx={{ display: 'flex', flexWrap: 'wrap', gap: 1, mt: 1 }}>
                          {Object.entries(result.intent.entities).map(([key, value]) => (
                            <Chip
                              key={key}
                              label={`${key}: ${value}`}
                              size="small"
                              variant="outlined"
                            />
                          ))}
                        </Box>
                      </Box>
                    </>
                  )}
                </CardContent>
              </Card>
            </Grid>

            {/* Metrics Card */}
            <Grid item xs={12} md={6}>
              <Card>
                <CardContent>
                  <Box display="flex" alignItems="center" mb={2}>
                    <CheckCircle sx={{ mr: 1, color: 'success.main' }} />
                    <Typography variant="h6">Processing Metrics</Typography>
                  </Box>

                  <Box sx={{ mb: 2 }}>
                    <Typography variant="body2" color="textSecondary">
                      Status
                    </Typography>
                    <Chip
                      label={result.status.toUpperCase()}
                      color="success"
                      size="medium"
                      sx={{ mt: 0.5 }}
                    />
                  </Box>

                  <Divider sx={{ my: 2 }} />

                  <Box sx={{ mb: 2 }}>
                    <Typography variant="body2" color="textSecondary">
                      Processing Time
                    </Typography>
                    <Typography variant="h6" sx={{ mt: 0.5 }}>
                      {result.processingTimeMs} ms
                    </Typography>
                  </Box>

                  <Divider sx={{ my: 2 }} />

                  <Box>
                    <Typography variant="body2" color="textSecondary">
                      Timestamp
                    </Typography>
                    <Typography variant="body1" sx={{ mt: 0.5 }}>
                      {new Date().toLocaleString()}
                    </Typography>
                  </Box>
                </CardContent>
              </Card>
            </Grid>
          </Grid>

          {/* Recommendations Table */}
          <Card>
            <CardContent>
              <Typography variant="h6" gutterBottom>
                Recommended Products ({result.recommendations?.length || 0})
              </Typography>

              {result.recommendations && result.recommendations.length > 0 ? (
                <Table>
                  <TableHead>
                    <TableRow>
                      <TableCell align="center" width="60">
                        <strong>Rank</strong>
                      </TableCell>
                      <TableCell>
                        <strong>Product Name</strong>
                      </TableCell>
                      <TableCell>
                        <strong>Category</strong>
                      </TableCell>
                      <TableCell align="center">
                        <strong>Score</strong>
                      </TableCell>
                      <TableCell>
                        <strong>Reason</strong>
                      </TableCell>
                      <TableCell>
                        <strong>Key Benefits</strong>
                      </TableCell>
                      <TableCell align="right">
                        <strong>Annual Fee</strong>
                      </TableCell>
                    </TableRow>
                  </TableHead>
                  <TableBody>
                    {result.recommendations.map((rec) => (
                      <TableRow key={rec.rank}>
                        <TableCell align="center">
                          <Chip
                            label={rec.rank}
                            size="small"
                            color={rec.rank === 1 ? 'primary' : 'default'}
                          />
                        </TableCell>
                        <TableCell>
                          <Typography variant="body2" fontWeight="medium">
                            {rec.productName}
                          </Typography>
                          {rec.islamicStructure && (
                            <Typography variant="caption" color="textSecondary">
                              {rec.islamicStructure}
                            </Typography>
                          )}
                        </TableCell>
                        <TableCell>
                          <Chip label={rec.category} size="small" color="primary" variant="outlined" />
                        </TableCell>
                        <TableCell align="center">
                          <Chip
                            label={formatConfidence(rec.relevanceScore)}
                            size="small"
                            color={getConfidenceColor(rec.relevanceScore)}
                          />
                        </TableCell>
                        <TableCell>
                          <Typography variant="body2">{rec.reason}</Typography>
                        </TableCell>
                        <TableCell>
                          {rec.keyBenefits && rec.keyBenefits.length > 0 ? (
                            <Box sx={{ display: 'flex', flexDirection: 'column', gap: 0.5 }}>
                              {rec.keyBenefits.slice(0, 3).map((benefit, idx) => (
                                <Typography key={idx} variant="caption" color="textSecondary">
                                  • {benefit}
                                </Typography>
                              ))}
                              {rec.keyBenefits.length > 3 && (
                                <Typography variant="caption" color="textSecondary">
                                  +{rec.keyBenefits.length - 3} more
                                </Typography>
                              )}
                            </Box>
                          ) : (
                            <Typography variant="caption" color="textSecondary">
                              N/A
                            </Typography>
                          )}
                        </TableCell>
                        <TableCell align="right">
                          <Typography variant="body2">
                            {rec.annualFee !== null && rec.annualFee !== undefined
                              ? `AED ${rec.annualFee.toFixed(2)}`
                              : 'N/A'}
                          </Typography>
                        </TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              ) : (
                <Alert severity="info" sx={{ mt: 2 }}>
                  No recommendations found for your query. Try a different query.
                </Alert>
              )}

              {result.message && (
                <Alert severity="info" sx={{ mt: 2 }}>
                  {result.message}
                </Alert>
              )}
            </CardContent>
          </Card>
        </>
      )}
    </Box>
  );
};

export default TestRecommendations;
