import React, { useState } from 'react';
import {
  AppBar,
  Toolbar,
  Typography,
  Container,
  Box,
  Button,
  Menu,
  MenuItem,
  ThemeProvider,
  createTheme,
  CssBaseline,
} from '@mui/material';
import {
  Dashboard as DashboardIcon,
  Storage as StorageIcon,
  Inventory as InventoryIcon,
  BugReport as BugReportIcon,
  ArrowDropDown,
} from '@mui/icons-material';
import Dashboard from './pages/Dashboard';
import ScrapeForm from './pages/ScrapeForm';
import StagingReview from './pages/StagingReview';
import TestRecommendations from './pages/TestRecommendations';
import AllProducts from './pages/AllProducts';

const theme = createTheme({
  palette: {
    primary: {
      main: '#1976d2',
    },
    secondary: {
      main: '#dc004e',
    },
  },
});

function App() {
  const [currentPage, setCurrentPage] = useState('dashboard');
  const [anchorEl, setAnchorEl] = useState({
    dataScraping: null,
    productManagement: null,
    testing: null,
  });

  const handleMenuOpen = (menu, event) => {
    setAnchorEl({ ...anchorEl, [menu]: event.currentTarget });
  };

  const handleMenuClose = (menu) => {
    setAnchorEl({ ...anchorEl, [menu]: null });
  };

  const handleNavigate = (page, menu = null) => {
    setCurrentPage(page);
    if (menu) {
      handleMenuClose(menu);
    }
  };

  const renderPage = () => {
    switch (currentPage) {
      case 'dashboard':
        return <Dashboard onNavigate={handleNavigate} />;
      case 'scrape':
        return <ScrapeForm />;
      case 'allProducts':
        return <AllProducts />;
      case 'stagingReview':
        return <StagingReview />;
      case 'testRecommendations':
        return <TestRecommendations />;
      default:
        return <Dashboard onNavigate={handleNavigate} />;
    }
  };

  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      <Box sx={{ display: 'flex', flexDirection: 'column', minHeight: '100vh' }}>
        <AppBar position="static">
          <Toolbar>
            <Typography variant="h6" component="div" sx={{ mr: 4 }}>
              Product Recommender Admin
            </Typography>

            {/* Dashboard Menu Item */}
            <Button
              color="inherit"
              startIcon={<DashboardIcon />}
              onClick={() => handleNavigate('dashboard')}
              sx={{
                mr: 1,
                backgroundColor: currentPage === 'dashboard' ? 'rgba(255,255,255,0.1)' : 'transparent',
              }}
            >
              Dashboard
            </Button>

            {/* Data Scraping Menu */}
            <Button
              color="inherit"
              startIcon={<StorageIcon />}
              endIcon={<ArrowDropDown />}
              onMouseEnter={(e) => handleMenuOpen('dataScraping', e)}
              sx={{ mr: 1 }}
            >
              Data Scraping
            </Button>
            <Menu
              anchorEl={anchorEl.dataScraping}
              open={Boolean(anchorEl.dataScraping)}
              onClose={() => handleMenuClose('dataScraping')}
              MenuListProps={{
                onMouseLeave: () => handleMenuClose('dataScraping'),
              }}
            >
              <MenuItem onClick={() => handleNavigate('scrape', 'dataScraping')}>
                Start Scrape
              </MenuItem>
            </Menu>

            {/* Product Management Menu */}
            <Button
              color="inherit"
              startIcon={<InventoryIcon />}
              endIcon={<ArrowDropDown />}
              onMouseEnter={(e) => handleMenuOpen('productManagement', e)}
              sx={{ mr: 1 }}
            >
              Product Management
            </Button>
            <Menu
              anchorEl={anchorEl.productManagement}
              open={Boolean(anchorEl.productManagement)}
              onClose={() => handleMenuClose('productManagement')}
              MenuListProps={{
                onMouseLeave: () => handleMenuClose('productManagement'),
              }}
            >
              <MenuItem onClick={() => handleNavigate('allProducts', 'productManagement')}>
                All Products
              </MenuItem>
              <MenuItem onClick={() => handleNavigate('stagingReview', 'productManagement')}>
                Staging Review
              </MenuItem>
            </Menu>

            {/* Testing Menu */}
            <Button
              color="inherit"
              startIcon={<BugReportIcon />}
              endIcon={<ArrowDropDown />}
              onMouseEnter={(e) => handleMenuOpen('testing', e)}
              sx={{ mr: 1 }}
            >
              Testing
            </Button>
            <Menu
              anchorEl={anchorEl.testing}
              open={Boolean(anchorEl.testing)}
              onClose={() => handleMenuClose('testing')}
              MenuListProps={{
                onMouseLeave: () => handleMenuClose('testing'),
              }}
            >
              <MenuItem onClick={() => handleNavigate('testRecommendations', 'testing')}>
                Test Recommendations
              </MenuItem>
            </Menu>
          </Toolbar>
        </AppBar>

        <Container maxWidth="xl" sx={{ mt: 4, mb: 4, flexGrow: 1 }}>
          <Box>{renderPage()}</Box>
        </Container>

        <Box component="footer" sx={{ py: 3, px: 2, mt: 'auto', backgroundColor: '#f5f5f5' }}>
          <Container maxWidth="xl">
            <Typography variant="body2" color="text.secondary" align="center">
              Product Recommender AI - Admin UI
            </Typography>
          </Container>
        </Box>
      </Box>
    </ThemeProvider>
  );
}

export default App;
