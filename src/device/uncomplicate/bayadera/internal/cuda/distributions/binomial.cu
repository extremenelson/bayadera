extern "C" {

    inline REAL lbinco(const REAL n, const REAL k) {
        return lgamma(n + 1) - lgamma(k + 1) - lgamma(n - k + 1);
    }

    inline REAL binomial_log_unscaled(const REAL n, const REAL p, const REAL k) {
        return (k * log(p)) + ((n - k) * log(1 - p));
    }

    inline REAL binomial_log(const REAL n, const REAL p, const REAL k) {
        return (k * log(p)) + ((n - k) * log(1 - p)) + lbinco(n, k);
    }

// ============= With params ========================================

    REAL binomial_mcmc_logpdf(const REAL* params, const REAL* k) {
        return binomial_log_unscaled(params[0], params[1], k[0]);
    }

    REAL binomial_logpdf(const REAL* params, const REAL* k) {
        return binomial_log(params[0], params[1], k[0]);
    }

    REAL binomial_loglik(const REAL* params, const REAL* p) {
        return binomial_log_unscaled(params[0], p[0], params[1]);
    }
}
