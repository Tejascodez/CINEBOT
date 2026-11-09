import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { ApiError } from './api/client'
import { AuthProvider } from './auth/AuthContext'
import { RequireAuth } from './auth/RequireAuth'
import { Layout } from './components/Layout'
import { AdminPage } from './pages/admin/AdminPage'
import { AuthPage } from './pages/AuthPage'
import { BookingPage } from './pages/BookingPage'
import { BookingsPage } from './pages/BookingsPage'
import { CheckoutPage } from './pages/CheckoutPage'
import { HomePage } from './pages/HomePage'
import { MovieDetailPage } from './pages/MovieDetailPage'
import { SeatSelectionPage } from './pages/SeatSelectionPage'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 15_000,
      // never retry client errors (4xx): they won't fix themselves
      retry: (count, error) => !(error instanceof ApiError && error.status < 500) && count < 2,
    },
  },
})

export function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <BrowserRouter>
          <Routes>
            <Route element={<Layout />}>
              <Route index element={<HomePage />} />
              <Route path="login" element={<AuthPage mode="login" />} />
              <Route path="register" element={<AuthPage mode="register" />} />
              <Route path="movies/:id" element={<MovieDetailPage />} />
              <Route path="shows/:showId/seats" element={<SeatSelectionPage />} />
              <Route path="bookings" element={<RequireAuth><BookingsPage /></RequireAuth>} />
              <Route path="bookings/:id" element={<RequireAuth><BookingPage /></RequireAuth>} />
              <Route path="checkout/:orderId" element={<RequireAuth><CheckoutPage /></RequireAuth>} />
              <Route path="admin" element={<RequireAuth role="ADMIN"><AdminPage /></RequireAuth>} />
              <Route path="*" element={<p className="p-8 text-center text-slate-400">Page not found.</p>} />
            </Route>
          </Routes>
        </BrowserRouter>
      </AuthProvider>
    </QueryClientProvider>
  )
}
