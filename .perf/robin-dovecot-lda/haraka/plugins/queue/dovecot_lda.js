'use strict'

const { spawn } = require('node:child_process')

exports.register = function () {
    this.activeCount = 0
    this.waiting = []
    this.load_lda_ini()
}

exports.load_lda_ini = function () {
    this.cfg = this.config.get('dovecot_lda.ini', () => {
        this.load_lda_ini()
    })
}

exports.get_lda_section = function () {
    return this.cfg.main || this.cfg
}

exports.acquire_slot = function () {
    const maxConcurrency = Math.max(1, Number(this.get_lda_section().maxConcurrency || 1))
    if (this.activeCount < maxConcurrency) {
        this.activeCount += 1
        return Promise.resolve()
    }

    return new Promise((resolve) => {
        this.waiting.push(resolve)
    })
}

exports.release_slot = function () {
    const next = this.waiting.shift()
    if (next) {
        next()
        return
    }

    this.activeCount = Math.max(0, this.activeCount - 1)
}

exports.run_with_slot = async function (task) {
    await this.acquire_slot()
    try {
        return await task()
    }
    finally {
        this.release_slot()
    }
}

exports.hook_queue = function (next, connection) {
    const txn = connection?.transaction
    if (!txn) return next()

    const recipients = txn.rcpt_to.map((rcpt) => rcpt.address()).filter(Boolean)
    if (!recipients.length) return next(DENY, 'No recipients')

    const sender = txn.mail_from?.address() || ''
    txn.message_stream.get_data(async (rawMessage) => {
        try {
            await this.run_with_slot(async () => {
                for (const recipient of recipients) {
                    await this.run_lda(connection, sender, recipient, rawMessage)
                }
            })
            next(OK)
        }
        catch (err) {
            connection.logerror(this, err.stack || err.message)
            next(DENYSOFT, `LDA delivery failed: ${err.message}`)
        }
    })
}

exports.run_lda = function (connection, sender, recipient, rawMessage) {
    const section = this.get_lda_section()
    const timeout = Number(section.timeout || 30000)
    const args = sender ? ['-f', sender, '-d', recipient] : ['-d', recipient]

    return new Promise((resolve, reject) => {
        const child = spawn(section.path || '/usr/lib/dovecot/dovecot-lda', args, {
            env: {
                ...process.env,
                HOME: section.home || '/var/mail/vhosts',
            },
            stdio: ['pipe', 'pipe', 'pipe'],
        })

        let stderr = ''
        let stdout = ''
        let settled = false

        const timer = setTimeout(() => {
            child.kill('SIGKILL')
            if (!settled) {
                settled = true
                reject(new Error(`dovecot-lda timed out after ${timeout}ms for ${recipient}`))
            }
        }, timeout)

        child.stdout.on('data', (chunk) => {
            stdout += chunk.toString()
        })

        child.stderr.on('data', (chunk) => {
            stderr += chunk.toString()
        })

        child.stdin.on('error', (err) => {
            if (!settled) {
                settled = true
                clearTimeout(timer)
                reject(err)
            }
        })

        child.on('error', (err) => {
            clearTimeout(timer)
            if (!settled) {
                settled = true
                reject(err)
            }
        })

        child.on('close', (code) => {
            clearTimeout(timer)
            if (settled) return
            settled = true
            if (code === 0) return resolve()
            reject(new Error(`dovecot-lda exited with ${code} for ${recipient}: ${stderr || stdout}`.trim()))
        })

        child.stdin.end(rawMessage)
    })
}
