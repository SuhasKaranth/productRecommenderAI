import React, { useState, useEffect } from 'react';
import {
  Box,
  Card,
  CardContent,
  Typography,
  Grid,
  Button,
  CircularProgress,
} from '@mui/material';
import { Assessment, CheckCircle, Pending, Cancel } from '@mui/icons-material';
import { stagingApi } from '../services/api';

const Dashboard = ({ onNavigate }) => {
  const [stats, setStats] = useState({ pending: 0, approved: 0, rejected: 0 });
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    loadStats();
  }, []);

  const loadStats = async () => {
    try {
      const response = await stagingApi.getStats();
      setStats(response.data);
    } catch (error) {
      console.error('Failed to load stats:', error);
    } finally {
      setLoading(false);
    }
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
      <Typography variant="h4" gutterBottom>
        Scraper Dashboard
      </Typography>

      <Grid container spacing={3} sx={{ mb: 4 }}>
        <Grid item xs={12} md={4}>
          <Card>
            <CardContent>
              <Box display="flex" alignItems="center" mb={2}>
                <Pending color="warning" sx={{ fontSize: 40, mr: 2 }} />
                <div>
                  <Typography color="textSecondary" gutterBottom>
                    Pending Review
                  </Typography>
                  <Typography variant="h4">{stats.pending}</Typography>
                </div>
              </Box>
            </CardContent>
          </Card>
        </Grid>

        <Grid item xs={12} md={4}>
          <Card>
            <CardContent>
              <Box display="flex" alignItems="center" mb={2}>
                <CheckCircle color="success" sx={{ fontSize: 40, mr: 2 }} />
                <div>
                  <Typography color="textSecondary" gutterBottom>
                    Approved
                  </Typography>
                  <Typography variant="h4">{stats.approved}</Typography>
                </div>
              </Box>
            </CardContent>
          </Card>
        </Grid>

        <Grid item xs={12} md={4}>
          <Card>
            <CardContent>
              <Box display="flex" alignItems="center" mb={2}>
                <Cancel color="error" sx={{ fontSize: 40, mr: 2 }} />
                <div>
                  <Typography color="textSecondary" gutterBottom>
                    Rejected
                  </Typography>
                  <Typography variant="h4">{stats.rejected}</Typography>
                </div>
              </Box>
            </CardContent>
          </Card>
        </Grid>
      </Grid>

      <Grid container spacing={3}>
        <Grid item xs={12} md={6}>
          <Card>
            <CardContent>
              <Typography variant="h6" gutterBottom>
                Quick Actions
              </Typography>
              <Box display="flex" flexDirection="column" gap={2} mt={2}>
                <Button
                  variant="contained"
                  fullWidth
                  onClick={() => onNavigate('scrape')}
                >
                  Start New Scrape
                </Button>
                <Button
                  variant="outlined"
                  fullWidth
                  onClick={() => onNavigate('review')}
                  disabled={stats.pending === 0}
                >
                  Review Pending Products ({stats.pending})
                </Button>
              </Box>
            </CardContent>
          </Card>
        </Grid>

        <Grid item xs={12} md={6}>
          <Card>
            <CardContent>
              <Typography variant="h6" gutterBottom>
                System Info
              </Typography>
              <Typography variant="body2" color="textSecondary">
                <Assessment sx={{ verticalAlign: 'middle', mr: 1 }} />
                Total Scraped Products: {stats.pending + stats.approved + stats.rejected}
              </Typography>
            </CardContent>
          </Card>
        </Grid>
      </Grid>
    </Box>
  );
};

export default Dashboard;
