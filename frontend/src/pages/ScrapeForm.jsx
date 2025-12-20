import React, { useState, useEffect } from 'react';
import {
  Box,
  Card,
  CardContent,
  Typography,
  TextField,
  Button,
  Select,
  MenuItem,
  FormControl,
  InputLabel,
  CircularProgress,
  Alert,
  LinearProgress,
} from '@mui/material';
import { PlayArrow, Refresh } from '@mui/icons-material';
import { scraperApi } from '../services/api';

const ScrapeForm = () => {
  const [url, setUrl] = useState('');
  const [urlError, setUrlError] = useState('');
  const [scraping, setScraping] = useState(false);
  const [message, setMessage] = useState(null);
  const [scrapedData, setScrapedData] = useState(null);

  const validateUrl = (url) => {
    try {
      const urlObj = new URL(url);
      if (!['http:', 'https:'].includes(urlObj.protocol)) {
        return 'URL must start with http:// or https://';
      }
      return '';
    } catch (error) {
      return 'Please enter a valid URL (e.g., https://example.com)';
    }
  };

  const handleUrlChange = (e) => {
    const inputUrl = e.target.value;
    setUrl(inputUrl);
    if (inputUrl) {
      setUrlError(validateUrl(inputUrl));
    } else {
      setUrlError('');
    }
  };

  const handleScrape = async () => {
    // Validate URL
    if (!url) {
      setMessage({ type: 'warning', text: 'Please enter a website URL' });
      return;
    }

    const error = validateUrl(url);
    if (error) {
      setUrlError(error);
      setMessage({ type: 'error', text: error });
      return;
    }

    setScraping(true);
    setMessage(null);
    setScrapedData(null);

    try {
      // MVP2: Use enhanced scraping with AI analysis
      const response = await scraperApi.scrapeUrlEnhanced(url);
      setScrapedData(response.data);
      setMessage({
        type: 'success',
        text: response.data.message || `Successfully scraped ${response.data.textLength || 0} characters from the page!`,
      });
    } catch (error) {
      setMessage({
        type: 'error',
        text: error.response?.data?.message || 'Failed to scrape the website',
      });
    } finally {
      setScraping(false);
    }
  };

  return (
    <Box>
      <Typography variant="h4" gutterBottom>
        Start New Scrape
      </Typography>

      <Card>
        <CardContent>
          <Box component="form" sx={{ display: 'flex', flexDirection: 'column', gap: 3 }}>
            {/* URL Input */}
            <TextField
              fullWidth
              label="Website URL"
              placeholder="https://adib.ae/en/personal/cards"
              value={url}
              onChange={handleUrlChange}
              disabled={scraping}
              error={!!urlError}
              helperText={urlError || 'Enter the full URL of the website to scrape (supports .com, .ae, .my, etc.)'}
              required
              autoFocus
            />

            {message && (
              <Alert severity={message.type} onClose={() => setMessage(null)}>
                {message.text}
              </Alert>
            )}

            {scraping && (
              <Box>
                <Typography variant="body2" color="textSecondary" gutterBottom>
                  Scraping website, analyzing with AI, and extracting products...
                </Typography>
                <LinearProgress />
              </Box>
            )}

            <Button
              variant="contained"
              size="large"
              startIcon={scraping ? <CircularProgress size={20} /> : <PlayArrow />}
              onClick={handleScrape}
              disabled={scraping || !url || !!urlError}
              fullWidth
            >
              {scraping ? 'Scraping...' : 'Scrape Website'}
            </Button>

            {scrapedData && (
              <Alert severity="success">
                <Typography variant="body2">
                  <strong>Scraping Complete!</strong>
                  <br />
                  • Page Title: {scrapedData.title || 'N/A'}
                  <br />
                  • Text Length: {scrapedData.textLength || 0} characters
                  <br />
                  • URL: {scrapedData.url}
                </Typography>
              </Alert>
            )}
          </Box>
        </CardContent>
      </Card>
    </Box>
  );
};

export default ScrapeForm;
