<?php

declare(strict_types=1);

namespace Railhook\Tests;

use PHPUnit\Framework\TestCase;
use Railhook\Railhook;

/**
 * Guards the published identity of this SDK: the Packagist package is
 * railhook/php and the PHP namespace is Railhook\. They agree, and this
 * exists to keep them agreeing.
 *
 * They used to disagree - the package was webhook-platform/php while the
 * namespace was Hookflow\ - which meant `composer require` and `use` needed
 * two unrelated names. That is what the rename to Railhook was for, and a
 * rename that touches one and not the other brings the old problem straight
 * back with nothing else in the build noticing.
 */
class PackageIdentityTest extends TestCase
{
    public function testPackagePublishedAsWebhookPlatformPhp(): void
    {
        $composerJson = json_decode(
            file_get_contents(__DIR__ . '/../composer.json'),
            true
        );

        $this->assertSame('railhook/php', $composerJson['name']);
    }

    public function testSmokeConstructsClientUnderRailhookNamespace(): void
    {
        $client = new Railhook('test_api_key');

        $this->assertInstanceOf(Railhook::class, $client);
    }
}
