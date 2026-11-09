import type { ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router-dom'
import { Spinner } from '../components/Feedback'
import { useAuth } from './AuthContext'

/** Route guard. Unauthenticated users go to /login and come back afterwards. */
export function RequireAuth({ children, role }: { children: ReactNode; role?: 'ADMIN' }) {
  const { user, ready } = useAuth()
  const location = useLocation()
  if (!ready) return <Spinner />
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />
  if (role && user.role !== role) return <p className="p-8 text-center text-slate-400">This page is for administrators.</p>
  return <>{children}</>
}
