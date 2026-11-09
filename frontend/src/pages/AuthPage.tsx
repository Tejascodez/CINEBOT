import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { ApiError } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { ErrorBanner } from '../components/Feedback'

export function AuthPage({ mode }: { mode: 'login' | 'register' }) {
  const { login, register } = useAuth()
  const navigate = useNavigate()
  const from = (useLocation().state as { from?: string } | null)?.from ?? '/'
  const [form, setForm] = useState({ email: '', password: '', fullName: '', phone: '' })
  const [error, setError] = useState<unknown>(null)
  const [busy, setBusy] = useState(false)
  const fieldErrors = error instanceof ApiError ? (error.problem.errors ?? []) : []
  const fieldError = (f: string) => fieldErrors.find((e) => e.field === f)?.message

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      if (mode === 'login') await login(form.email, form.password)
      else await register({ email: form.email, password: form.password, fullName: form.fullName, phone: form.phone || undefined })
      navigate(from, { replace: true })
    } catch (err) {
      setError(err)
    } finally {
      setBusy(false)
    }
  }

  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => setForm({ ...form, [k]: e.target.value })

  return (
    <div className="mx-auto mt-10 max-w-sm">
      <h1 className="mb-6 text-2xl font-bold">{mode === 'login' ? 'Sign in' : 'Create your account'}</h1>
      <form onSubmit={submit} className="card space-y-4" noValidate>
        {mode === 'register' && (
          <div>
            <label className="label" htmlFor="fullName">Full name</label>
            <input id="fullName" className="input" value={form.fullName} onChange={set('fullName')} required />
            {fieldError('fullName') && <p className="mt-1 text-xs text-rose-400">{fieldError('fullName')}</p>}
          </div>
        )}
        <div>
          <label className="label" htmlFor="email">Email</label>
          <input id="email" type="email" autoComplete="email" className="input" value={form.email} onChange={set('email')} required />
          {fieldError('email') && <p className="mt-1 text-xs text-rose-400">{fieldError('email')}</p>}
        </div>
        <div>
          <label className="label" htmlFor="password">Password</label>
          <input id="password" type="password" autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
                 className="input" value={form.password} onChange={set('password')} required minLength={8} />
          {fieldError('password') && <p className="mt-1 text-xs text-rose-400">{fieldError('password')}</p>}
        </div>
        {mode === 'register' && (
          <div>
            <label className="label" htmlFor="phone">Phone (optional)</label>
            <input id="phone" className="input" value={form.phone} onChange={set('phone')} placeholder="+919876543210" />
          </div>
        )}
        {fieldErrors.length === 0 && <ErrorBanner error={error} />}
        <button className="btn-primary w-full" disabled={busy}>{busy ? 'Please wait…' : mode === 'login' ? 'Sign in' : 'Create account'}</button>
      </form>
      <p className="mt-4 text-center text-sm text-slate-400">
        {mode === 'login' ? <>New here? <Link className="text-rose-400" to="/register" state={{ from }}>Create an account</Link></>
          : <>Already have an account? <Link className="text-rose-400" to="/login" state={{ from }}>Sign in</Link></>}
      </p>
    </div>
  )
}
