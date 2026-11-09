import { NavLink, Outlet, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { ChatWidget } from './chat/ChatWidget'
import { CityPicker } from './CityPicker'

export function Layout() {
  const { user, logout } = useAuth()
  const navigate = useNavigate()
  const link = ({ isActive }: { isActive: boolean }) =>
    `rounded-md px-3 py-1.5 text-sm ${isActive ? 'bg-slate-800 text-white' : 'text-slate-300 hover:text-white'}`

  return (
    <div className="min-h-screen">
      <header className="sticky top-0 z-10 border-b border-slate-800 bg-slate-950/90 backdrop-blur">
        <nav className="mx-auto flex max-w-6xl items-center gap-2 px-4 py-3">
          <NavLink to="/" className="mr-4 text-lg font-black tracking-tight text-rose-500">CineBot</NavLink>
          <CityPicker />
          <NavLink to="/" end className={link}>Movies</NavLink>
          {user && <NavLink to="/bookings" className={link}>My bookings</NavLink>}
          {user?.role === 'ADMIN' && <NavLink to="/admin" className={link}>Admin</NavLink>}
          <span className="ml-auto" />
          {user ? (
            <>
              <span className="hidden text-sm text-slate-400 sm:inline">{user.fullName}</span>
              <button className="btn-ghost" onClick={() => logout().then(() => navigate('/'))}>Sign out</button>
            </>
          ) : (
            <NavLink to="/login" className="btn-primary">Sign in</NavLink>
          )}
        </nav>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-6 pb-24">
        <Outlet />
      </main>
      <ChatWidget />
    </div>
  )
}
