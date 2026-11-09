import { useMutation } from '@tanstack/react-query'
import { useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { paymentApi } from '../api/endpoints'
import type { PaymentOrder } from '../api/types'
import { ErrorBanner } from '../components/Feedback'
import { money } from '../lib/format'

/**
 * Stands in for the payment provider's hosted checkout (simulated provider). A real provider page would
 * return the same thing: orderId + paymentId + signature, which we pass to /payments/verify. The backend
 * then asks the provider itself whether the money was captured: the browser's word is never enough.
 */
export function CheckoutPage() {
  const { orderId = '' } = useParams()
  const bookingId = Number(useSearchParams()[0].get('bookingId'))
  const order = (useLocation().state as { order?: PaymentOrder } | null)?.order
  const navigate = useNavigate()

  const attempt = useMutation({
    mutationFn: async (outcome: 'SUCCESS' | 'FAILURE') => {
      const result = await paymentApi.checkout(orderId, outcome)
      if (result.status === 'CAPTURED' && result.signature) {
        await paymentApi.verify({ orderId, paymentId: result.paymentId, signature: result.signature })
      }
      return result
    },
    // Failure: the provider's webhook updates the booking a moment later; the booking page polls for it.
    onSettled: () => navigate(`/bookings/${bookingId}`, { replace: true }),
  })

  return (
    <div className="mx-auto mt-10 max-w-sm">
      <div className="rounded-2xl border border-indigo-800 bg-indigo-950/40 p-6 shadow-xl">
        <p className="text-xs font-semibold uppercase tracking-widest text-indigo-300">CineBot Pay · simulated</p>
        <p className="mt-4 text-3xl font-bold">{order ? money(order.amountPaise) : 'Payment'}</p>
        <p className="mt-1 font-mono text-xs text-slate-500">{orderId}</p>
        <p className="mt-6 text-sm text-slate-300">
          No real money moves. Choose what the "bank" does, to see how CineBot handles each case.
        </p>
        <div className="mt-6 grid grid-cols-2 gap-3">
          <button className="btn bg-emerald-600 text-white hover:bg-emerald-500" disabled={attempt.isPending}
                  onClick={() => attempt.mutate('SUCCESS')}>Pay</button>
          <button className="btn bg-slate-700 text-white hover:bg-slate-600" disabled={attempt.isPending}
                  onClick={() => attempt.mutate('FAILURE')}>Decline</button>
        </div>
        <div className="mt-4"><ErrorBanner error={attempt.error} /></div>
      </div>
    </div>
  )
}
